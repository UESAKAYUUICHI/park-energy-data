package com.parkenergydata.repository;

import com.parkenergydata.dto.AccessForwardMessage;
import com.parkenergydata.dto.MeterPayload;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

/** Per-device idempotency and diagnostics under one gateway upload. */
@Repository
public class DataIngestItemRepository {
    private final JdbcTemplate jdbcTemplate;

    public DataIngestItemRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean tryClaim(AccessForwardMessage forward, MeterPayload meter, Instant collectTime) {
        String deviceSn = meter == null ? null : meter.deviceSn();
        if (forward.rawLogId() <= 0 || deviceSn == null || deviceSn.isBlank()) {
            return true;
        }
        int inserted = jdbcTemplate.update("""
                INSERT IGNORE INTO data_ingest_item
                  (raw_log_id, gateway_id, device_sn, channel_id, modbus_addr, profile_key, model_version,
                   config_revision, collect_time, status, received_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PROCESSING', ?)
                """, forward.rawLogId(), forward.gatewayId(), deviceSn, blankToNull(meter.channelId()), meter.modbusAddr(),
                blankToNull(meter.profileKey()), blankToNull(meter.modelVersion()), blankToNull(meter.configRevision()),
                timestamp(collectTime), timestamp(forward.receivedAt()));
        if (inserted == 1) return true;
        return jdbcTemplate.update("""
                UPDATE data_ingest_item
                SET status = 'PROCESSING', processed_at = NULL, error_code = NULL, error_reason = NULL,
                    retry_count = retry_count + 1, channel_id = ?, modbus_addr = ?, profile_key = ?,
                    model_version = ?, config_revision = ?
                WHERE raw_log_id = ? AND device_sn = ?
                  AND status IN ('INVALID', 'DEAD_LETTER', 'REPLAY_REQUESTED')
                """, blankToNull(meter.channelId()), meter.modbusAddr(), blankToNull(meter.profileKey()),
                blankToNull(meter.modelVersion()), blankToNull(meter.configRevision()), forward.rawLogId(),
                deviceSn) == 1;
    }

    /** Compatibility overload for older unit tests and replayers. */
    public boolean tryClaim(AccessForwardMessage forward, String deviceSn, Instant collectTime) {
        if (forward.rawLogId() <= 0 || deviceSn == null || deviceSn.isBlank()) return true;
        int inserted = jdbcTemplate.update("""
                INSERT IGNORE INTO data_ingest_item
                  (raw_log_id, gateway_id, device_sn, collect_time, status, received_at)
                VALUES (?, ?, ?, ?, 'PROCESSING', ?)
                """, forward.rawLogId(), forward.gatewayId(), deviceSn, timestamp(collectTime), timestamp(forward.receivedAt()));
        if (inserted == 1) return true;
        return jdbcTemplate.update("""
                UPDATE data_ingest_item
                SET status = 'PROCESSING', processed_at = NULL, error_code = NULL, error_reason = NULL, retry_count = retry_count + 1
                WHERE raw_log_id = ? AND device_sn = ?
                  AND status IN ('INVALID', 'DEAD_LETTER', 'REPLAY_REQUESTED')
                """, forward.rawLogId(), deviceSn) == 1;
    }

    public void markSuccess(AccessForwardMessage forward, String deviceSn, Instant collectTime, Long deviceId) {
        if (forward.rawLogId() <= 0 || deviceSn == null || deviceSn.isBlank()) return;
        jdbcTemplate.update("""
                UPDATE data_ingest_item
                SET status = 'SUCCESS', device_id = ?, processed_at = CURRENT_TIMESTAMP, error_code = NULL, error_reason = NULL
                WHERE raw_log_id = ? AND device_sn = ? AND status = 'PROCESSING'
                """, deviceId, forward.rawLogId(), deviceSn);
    }

    public void markInvalid(AccessForwardMessage forward, String deviceSn, Instant collectTime, String reason) {
        if (forward.rawLogId() <= 0 || deviceSn == null || deviceSn.isBlank()) return;
        jdbcTemplate.update("""
                UPDATE data_ingest_item
                SET status = 'INVALID', processed_at = CURRENT_TIMESTAMP, error_code = ?, error_reason = ?
                WHERE raw_log_id = ? AND device_sn = ? AND status = 'PROCESSING'
                """, failureCode(reason), shortReason(reason), forward.rawLogId(), deviceSn);
    }

    private Timestamp timestamp(Instant value) {
        return Timestamp.from(value == null ? Instant.now() : value);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String shortReason(String reason) {
        String value = reason == null || reason.isBlank() ? "invalid device payload" : reason;
        value = value.replaceAll("\\s+", " ").trim();
        return value.substring(0, Math.min(value.length(), 460));
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
