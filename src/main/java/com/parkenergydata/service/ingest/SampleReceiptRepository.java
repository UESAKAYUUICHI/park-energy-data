package com.parkenergydata.service.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.util.HexFormat;
import java.util.TreeMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SampleReceiptRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public SampleReceiptRepository(JdbcTemplate jdbc,ObjectMapper json) { this.jdbc=jdbc; this.json=json; }
    public boolean claim(PreparedTelemetry sample) {
        TreeMap<String,Object> points=new TreeMap<>();
        sample.points().forEach(p->points.put(p.pointCode(),p.value()));
        String hash;
        try {
            String input=json.writeValueAsString(points)+"|"+sample.meter().quality();
            hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch(Exception e) { throw new IllegalStateException("Cannot fingerprint sample",e); }
        Timestamp time=Timestamp.from(sample.collectedAt());
        int inserted=jdbc.update("""
                INSERT IGNORE INTO data_sample_receipt(device_id,collect_time,content_hash)
                VALUES (?,?,?)
                """,sample.device().id(),time,hash);
        if(inserted==1) return true;
        String previous=jdbc.queryForObject("""
                SELECT content_hash FROM data_sample_receipt WHERE device_id=? AND collect_time=? FOR UPDATE
                """,String.class,sample.device().id(),time);
        if(!hash.equals(previous)) throw new SampleConflictException();
        return false;
    }
}
