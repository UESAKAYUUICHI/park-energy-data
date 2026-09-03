package com.parkenergydata.repository;

import java.sql.Timestamp;
import java.time.Instant;

import com.parkenergydata.dto.AccessForwardMessage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 数据服务的持久化幂等门闩；Redis 仅作缓存，不再作为结算数据的唯一去重依据。 */
@Repository
public class DataIngestEventRepository {
    private final JdbcTemplate jdbcTemplate;

    public DataIngestEventRepository(JdbcTemplate jdbcTemplate) { this.jdbcTemplate = jdbcTemplate; }

    public String key(AccessForwardMessage forward) {
        if (forward.rawLogId() > 0) return "RAW:" + forward.rawLogId() + ":" + forward.messageId();
        return "GW:" + forward.gatewayId() + ":" + forward.messageId();
    }

    public boolean tryClaim(AccessForwardMessage forward) {
        Instant received = forward.receivedAt() == null ? Instant.now() : forward.receivedAt();
        int affected = jdbcTemplate.update("""
                INSERT IGNORE INTO data_ingest_event
                  (ingest_key, raw_log_id, gateway_id, message_id, status, received_at)
                VALUES (?, ?, ?, ?, 'PROCESSING', ?)
                """, key(forward), forward.rawLogId() > 0 ? forward.rawLogId() : null, forward.gatewayId(),
                forward.messageId(), Timestamp.from(received));
        if (affected == 1) return true;
        return jdbcTemplate.update("""
                UPDATE data_ingest_event SET status = 'PROCESSING', processed_at = NULL, error_code = NULL, error_reason = NULL
                WHERE ingest_key = ? AND status = 'REPLAY_REQUESTED'
                """, key(forward)) == 1;
    }

    public void markSuccess(AccessForwardMessage forward, int meterCount) {
        jdbcTemplate.update("""
                UPDATE data_ingest_event
                SET status = 'SUCCESS', processed_at = CURRENT_TIMESTAMP, meter_count = ?, error_code = NULL, error_reason = NULL
                WHERE ingest_key = ?
                """, meterCount, key(forward));
    }

    public void markInvalid(AccessForwardMessage forward, String reason) {
        Instant received = forward.receivedAt() == null ? Instant.now() : forward.receivedAt();
        jdbcTemplate.update("""
                INSERT INTO data_ingest_event
                  (ingest_key, raw_log_id, gateway_id, message_id, status, received_at, processed_at, error_code, error_reason)
                VALUES (?, ?, ?, ?, 'INVALID', ?, CURRENT_TIMESTAMP, ?, ?)
                ON DUPLICATE KEY UPDATE
                  status = IF(status = 'SUCCESS', status, 'INVALID'),
                  processed_at = IF(status = 'SUCCESS', processed_at, CURRENT_TIMESTAMP),
                  error_code = IF(status = 'SUCCESS', error_code, VALUES(error_code)),
                  error_reason = IF(status = 'SUCCESS', error_reason, VALUES(error_reason))
                """, key(forward), forward.rawLogId() > 0 ? forward.rawLogId() : null, forward.gatewayId(),
                forward.messageId(), Timestamp.from(received), failureCode(reason), shortReason(reason));
    }

    public void markDeadLetter(AccessForwardMessage forward, String reason) {
        Instant received = forward.receivedAt() == null ? Instant.now() : forward.receivedAt();
        jdbcTemplate.update("""
                INSERT INTO data_ingest_event
                  (ingest_key, raw_log_id, gateway_id, message_id, status, received_at, processed_at, error_code, error_reason)
                VALUES (?, ?, ?, ?, 'DEAD_LETTER', ?, CURRENT_TIMESTAMP, ?, ?)
                ON DUPLICATE KEY UPDATE
                  status = IF(status = 'SUCCESS', status, 'DEAD_LETTER'),
                  processed_at = IF(status = 'SUCCESS', processed_at, CURRENT_TIMESTAMP),
                  error_code = IF(status = 'SUCCESS', error_code, VALUES(error_code)),
                  error_reason = IF(status = 'SUCCESS', error_reason, VALUES(error_reason))
                """, key(forward), forward.rawLogId() > 0 ? forward.rawLogId() : null, forward.gatewayId(),
                forward.messageId(), Timestamp.from(received), "PROCESSING_FAILED", shortReason(reason));
    }

    public boolean requestReplay(long eventId) {
        return jdbcTemplate.update("""
                UPDATE data_ingest_event
                SET status = 'REPLAY_REQUESTED', processed_at = NULL, error_code = NULL, error_reason = NULL
        WHERE id = ? AND status IN ('SUCCESS', 'INVALID', 'DEAD_LETTER')
                """, eventId) == 1;
    }

    private String shortReason(String reason) {
        if (reason == null || reason.isBlank()) return "invalid payload";
        return reason.replaceAll("\\s+", " ").trim().substring(0, Math.min(reason.replaceAll("\\s+", " ").trim().length(), 460));
    }

    private String failureCode(String reason) {
        String value = reason == null ? "" : reason.toUpperCase();
        if (value.contains("REQUIRED POINT") || value.contains("MISSING")) return "REQUIRED_POINT_MISSING";
        if (value.contains("RATE LIMIT")) return "RATE_LIMITED";
        if (value.contains("NO DEVICE SAMPLE") || (value.contains("DEVICE") && (value.contains("NOT FOUND") || value.contains("NOT ACCEPTED") || value.contains("WERE ACCEPTED")))) return "DEVICE_SAMPLE_REJECTED";
        if (value.contains("SQL") || value.contains("DATABASE")) return "DB_WRITE_FAILED";
        return "RAW_PAYLOAD_INVALID";
    }
}
