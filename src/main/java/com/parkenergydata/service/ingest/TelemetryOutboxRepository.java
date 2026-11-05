package com.parkenergydata.service.ingest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TelemetryOutboxRepository {
    public record Pending(long id,String payload,int attempts) {}
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public TelemetryOutboxRepository(JdbcTemplate jdbc,ObjectMapper json) { this.jdbc=jdbc; this.json=json; }
    public void append(TelemetryProjection projection) {
        try {
            jdbc.update("INSERT INTO data_projection_outbox(device_id,payload) VALUES (?,?)",
                    projection.deviceId(),json.writeValueAsString(projection));
        } catch(JsonProcessingException e) { throw new IllegalStateException("Cannot serialize projection",e); }
    }
    public List<Pending> lockPending() {
        return jdbc.query("""
                SELECT id,payload,attempts FROM data_projection_outbox
                WHERE status='PENDING' AND next_attempt_at<=CURRENT_TIMESTAMP(3)
                ORDER BY id LIMIT 10 FOR UPDATE SKIP LOCKED
                """,(rs,n)->new Pending(rs.getLong(1),rs.getString(2),rs.getInt(3)));
    }
    public void complete(long id) {
        jdbc.update("""
                UPDATE data_projection_outbox SET status='DONE',completed_at=CURRENT_TIMESTAMP(3),last_error=NULL
                WHERE id=?
                """,id);
    }
    public void retry(Pending item,Exception failure) {
        int seconds=Math.min(300,1<<Math.min(item.attempts()+1,8));
        String reason=failure.getClass().getSimpleName()+": "+String.valueOf(failure.getMessage());
        reason=reason.substring(0,Math.min(reason.length(),500));
        jdbc.update("""
                UPDATE data_projection_outbox SET attempts=attempts+1,last_error=?,
                  next_attempt_at=TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(3)) WHERE id=?
                """,reason,seconds,item.id());
    }
    public TelemetryProjection decode(String payload) {
        try { return json.readValue(payload,TelemetryProjection.class); }
        catch(JsonProcessingException e) { throw new IllegalStateException("Invalid projection payload",e); }
    }
}
