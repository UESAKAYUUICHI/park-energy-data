package com.parkenergydata.repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MeterReadingStateRepository {
    private final JdbcTemplate jdbcTemplate;
    public MeterReadingStateRepository(JdbcTemplate jdbcTemplate) { this.jdbcTemplate = jdbcTemplate; }

    public Reading lock(long deviceId, String pointCode) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT last_collect_time, reading_value FROM data_meter_reading_state
                WHERE device_id = ? AND point_code = ? FOR UPDATE
        """, deviceId, pointCode);
        if (rows.isEmpty()) return null;
        Map<String, Object> row = rows.get(0);
        return new Reading(toInstant(row.get("last_collect_time")), new BigDecimal(String.valueOf(row.get("reading_value"))));
    }

    private Instant toInstant(Object value) {
        if (value instanceof Timestamp timestamp) return timestamp.toInstant();
        if (value instanceof LocalDateTime dateTime) return dateTime.atZone(ZoneId.systemDefault()).toInstant();
        if (value instanceof Instant instant) return instant;
        return Instant.parse(String.valueOf(value));
    }

    public void save(long deviceId, String pointCode, Instant collectTime, BigDecimal value) {
        jdbcTemplate.update("""
                INSERT INTO data_meter_reading_state (device_id, point_code, last_collect_time, reading_value)
                VALUES (?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE last_collect_time = VALUES(last_collect_time), reading_value = VALUES(reading_value)
                """, deviceId, pointCode, Timestamp.from(collectTime), value);
    }

    public record Reading(Instant collectTime, BigDecimal value) { }
}
