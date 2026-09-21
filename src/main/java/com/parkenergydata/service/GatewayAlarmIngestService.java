package com.parkenergydata.service;

import com.parkenergydata.common.BusinessException;
import com.parkenergydata.dto.AccessForwardMessage;
import com.parkenergydata.dto.GatewayAlarmPayload;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
public class GatewayAlarmIngestService {
    private final JdbcTemplate jdbcTemplate;

    public GatewayAlarmIngestService(JdbcTemplate jdbcTemplate) { this.jdbcTemplate = jdbcTemplate; }

    @Transactional
    public void ingest(AccessForwardMessage forward, GatewayAlarmPayload payload) {
        validate(payload);
        Map<String, Object> gateway = one("SELECT id,org_id FROM dev_gateway WHERE id=? AND status=1", forward.gatewayId());
        Map<String, Object> device = payload.deviceSn() == null ? null : optional(
                "SELECT id,org_id,space_id FROM dev_device WHERE gateway_id=? AND device_sn=? LIMIT 1",
                forward.gatewayId(), payload.deviceSn());
        Long deviceId = device == null ? null : number(device.get("id"));
        Object orgId = device == null ? gateway.get("org_id") : device.get("org_id");
        Object spaceId = device == null ? null : device.get("space_id");
        String fingerprint = "GATEWAY:" + forward.gatewayId() + ":" + payload.eventId();
        Timestamp eventTime = Timestamp.from(payload.timestamp() > 0
                ? Instant.ofEpochMilli(payload.timestamp()) : Instant.now());
        if ("RECOVERED".equals(payload.action())) {
            jdbcTemplate.update("""
                    UPDATE log_alarm SET event_status='RECOVERED',condition_status='CLEARED',recovery_time=?,
                      alarm_value=COALESCE(?, alarm_value), threshold_value=COALESCE(?, threshold_value),
                      active_fingerprint=NULL,last_occurrence_time=?,version=version+1
                    WHERE alarm_source='GATEWAY' AND source_gateway_id=? AND source_event_id=?
                    """, eventTime, alarmValue(payload), thresholdValue(payload), eventTime, forward.gatewayId(), payload.eventId());
            return;
        }
        jdbcTemplate.update("""
                INSERT INTO log_alarm
                  (alarm_source,source_gateway_id,source_event_id,device_id,org_id,space_id,alarm_type,alarm_level,
                   point_code,alarm_value,threshold_value,alarm_time,event_status,condition_status,event_fingerprint,
                   active_fingerprint,occurrence_count,first_occurrence_time,last_occurrence_time,deal_status)
                VALUES ('GATEWAY',?,?,?,?,?,?,?,?,?,?,?,'NEW','ACTIVE',?,?,1,?,?,0)
                ON DUPLICATE KEY UPDATE device_id=VALUES(device_id),org_id=VALUES(org_id),space_id=VALUES(space_id),
                  alarm_type=VALUES(alarm_type),alarm_level=VALUES(alarm_level),point_code=VALUES(point_code),
                  alarm_value=VALUES(alarm_value),threshold_value=VALUES(threshold_value),alarm_time=VALUES(alarm_time),event_status='NEW',
                  condition_status='ACTIVE',active_fingerprint=VALUES(active_fingerprint),recovery_time=NULL,
                  last_occurrence_time=VALUES(last_occurrence_time),occurrence_count=occurrence_count+1,version=version+1
                """, forward.gatewayId(), payload.eventId(), deviceId, orgId, spaceId, alarmType(payload.alarmType()),
                level(payload.level()), payload.pointCode(), alarmValue(payload), thresholdValue(payload), eventTime,
                fingerprint, fingerprint, eventTime, eventTime);
    }

    private void validate(GatewayAlarmPayload payload) {
        if (payload.eventId() == null || payload.eventId().isBlank()) throw new BusinessException("eventId is required");
        if (!"RAISED".equals(payload.action()) && !"RECOVERED".equals(payload.action())) {
            throw new BusinessException("unsupported alarm action: " + payload.action());
        }
    }

    private int level(String value) {
        return switch (value == null ? "" : value.toUpperCase()) {
            case "CRITICAL" -> 4;
            case "MAJOR", "ERROR" -> 3;
            case "MINOR", "WARN" -> 2;
            default -> 1;
        };
    }

    /** 将边缘侧语义告警类型收敛到平台现有 alarm_type 数字枚举。 */
    private int alarmType(String value) {
        String type = value == null ? "" : value.toUpperCase();
        if (type.contains("VOLTAGE")) return 1;
        if (type.contains("CURRENT")) return 2;
        if (type.contains("POWER") || type.contains("ENERGY")) return 3;
        if (type.contains("OFFLINE") || type.contains("CHANNEL")
                || type.contains("COMMUNICATION") || type.contains("SERIAL")) return 4;
        return 5;
    }

    private String alarmValue(GatewayAlarmPayload payload) {
        return payload.alarmValue() == null ? payload.message() : String.valueOf(payload.alarmValue());
    }

    private String thresholdValue(GatewayAlarmPayload payload) {
        if (payload.thresholdValue() != null) return String.valueOf(payload.thresholdValue());
        return payload.compareOperator() == null ? null : payload.compareOperator();
    }

    private Map<String, Object> one(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, args);
        if (rows.isEmpty()) throw new BusinessException("gateway not found");
        return rows.get(0);
    }
    private Map<String, Object> optional(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, args);
        return rows.isEmpty() ? null : rows.get(0);
    }
    private Long number(Object value) { return value == null ? null : ((Number) value).longValue(); }
}
