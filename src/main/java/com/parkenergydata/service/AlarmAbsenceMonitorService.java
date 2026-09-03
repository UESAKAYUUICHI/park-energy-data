package com.parkenergydata.service;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Creates OFFLINE/MISSING_DATA incidents from collection freshness, independent of incoming messages. */
@Service
public class AlarmAbsenceMonitorService {
    private static final Logger log = LoggerFactory.getLogger(AlarmAbsenceMonitorService.class);
    private final JdbcTemplate jdbcTemplate;

    public AlarmAbsenceMonitorService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Scheduled(fixedDelayString = "${park.alarm.absence-interval-ms:10000}",
            initialDelayString = "${park.alarm.absence-initial-delay-ms:20000}")
    public void monitor() {
        Integer locked = jdbcTemplate.queryForObject("SELECT GET_LOCK('park_alarm_absence_monitor',0)", Integer.class);
        if (locked == null || locked != 1) return;
        try {
            for (Map<String, Object> candidate : staleCandidates()) trigger(candidate);
            monitorGatewayHeartbeats();
        } catch (RuntimeException failure) {
            log.error("Alarm absence monitoring failed", failure);
        } finally {
            jdbcTemplate.queryForObject("SELECT RELEASE_LOCK('park_alarm_absence_monitor')", Integer.class);
        }
    }

    /** Access 在 3×30 秒无心跳后置网关离线；Data 在统一告警表生成/恢复平台告警。 */
    private void monitorGatewayHeartbeats() {
        for (Map<String, Object> gateway : jdbcTemplate.queryForList("""
                SELECT id,org_id,gateway_sn,last_online_time FROM dev_gateway
                WHERE status=1 AND online_status=0
                  AND (last_online_time IS NULL OR TIMESTAMPDIFF(SECOND,last_online_time,NOW())>=90)
                """)) {
            long gatewayId = number(gateway.get("id"));
            String fingerprint = "GATEWAY_OFFLINE:" + gatewayId;
            jdbcTemplate.update("""
                    INSERT INTO log_alarm
                      (alarm_source,source_gateway_id,source_event_id,device_id,org_id,alarm_type,alarm_level,
                       alarm_value,threshold_value,alarm_time,event_status,condition_status,event_fingerprint,
                       active_fingerprint,occurrence_count,first_occurrence_time,last_occurrence_time,deal_status)
                    VALUES ('PLATFORM',?,?,NULL,?,4,2,'GATEWAY_OFFLINE','90s',NOW(),'NEW','ACTIVE',?,?,1,NOW(),NOW(),0)
                    ON DUPLICATE KEY UPDATE alarm_time=NOW(),last_occurrence_time=NOW(),event_status='NEW',
                      condition_status='ACTIVE',active_fingerprint=VALUES(active_fingerprint),recovery_time=NULL,
                      occurrence_count=occurrence_count+IF(TIMESTAMPDIFF(SECOND,last_occurrence_time,NOW())>=60,1,0),version=version+1
                    """, gatewayId, fingerprint, gateway.get("org_id"), fingerprint, fingerprint);
        }
        jdbcTemplate.update("""
                UPDATE log_alarm a JOIN dev_gateway g ON g.id=a.source_gateway_id
                SET a.event_status='RECOVERED',a.condition_status='CLEARED',a.recovery_time=NOW(),
                    a.active_fingerprint=NULL,a.version=a.version+1
                WHERE a.source_event_id=CONCAT('GATEWAY_OFFLINE:',g.id) AND a.condition_status='ACTIVE'
                  AND g.online_status=1
                """);
    }

    private List<Map<String, Object>> staleCandidates() {
        return jdbcTemplate.queryForList("""
                WITH RECURSIVE org_tree AS (
                  SELECT id AS ancestor_id,id AS descendant_id FROM dev_org
                  UNION ALL
                  SELECT tree.ancestor_id,child.id
                  FROM org_tree tree JOIN dev_org child ON child.parent_id=tree.descendant_id
                ), latest_device AS (
                  SELECT device_id,MAX(last_collect_time) AS last_collect_time
                  FROM stats_collection_daily GROUP BY device_id
                ), latest_point AS (
                  SELECT device_id,point_code,MAX(last_collect_time) AS last_collect_time
                  FROM stats_daily_point GROUP BY device_id,point_code
                )
                SELECT r.id AS rule_id,r.published_version_id AS rule_version_id,v.alarm_type,v.alarm_level,
                       v.point_code,v.evaluation_mode,v.freshness_seconds,d.id AS device_id,d.org_id,d.space_id,
                       CASE WHEN v.evaluation_mode='OFFLINE' THEN COALESCE(ld.last_collect_time,d.create_time)
                            ELSE COALESCE(lp.last_collect_time,d.create_time) END AS last_sample_time
                FROM alarm_rule r
                JOIN alarm_rule_version v ON v.id=r.published_version_id
                JOIN dev_device d ON d.status=1 AND (
                  v.rule_scope=1 OR (v.rule_scope=3 AND v.device_id=d.id)
                  OR (v.rule_scope=2 AND (v.org_id=d.org_id OR (v.org_include_children=1 AND EXISTS (
                    SELECT 1 FROM org_tree tree WHERE tree.ancestor_id=v.org_id AND tree.descendant_id=d.org_id
                  ))))
                  OR (v.rule_scope=4 AND v.space_id=d.space_id)
                )
                LEFT JOIN latest_device ld ON ld.device_id=d.id
                LEFT JOIN latest_point lp ON lp.device_id=d.id AND lp.point_code=v.point_code
                WHERE r.enabled=1 AND v.evaluation_mode IN ('OFFLINE','MISSING_DATA')
                  AND TIMESTAMPDIFF(SECOND,
                    CASE WHEN v.evaluation_mode='OFFLINE' THEN COALESCE(ld.last_collect_time,d.create_time)
                         ELSE COALESCE(lp.last_collect_time,d.create_time) END,NOW())>=v.freshness_seconds
                ORDER BY v.alarm_level DESC,d.id LIMIT 200
                """);
    }

    private void trigger(Map<String, Object> row) {
        long ruleId = number(row.get("rule_id"));
        long deviceId = number(row.get("device_id"));
        String fingerprint = "RULE:" + ruleId + ":DEVICE:" + deviceId;
        Long existing = jdbcTemplate.query("SELECT id FROM log_alarm WHERE active_fingerprint=? LIMIT 1",
                rs -> rs.next() ? rs.getLong(1) : null, fingerprint);
        String value = "OFFLINE".equals(row.get("evaluation_mode")) ? "DEVICE_OFFLINE" : "POINT_MISSING";
        jdbcTemplate.update("""
                INSERT INTO log_alarm
                  (alarm_source,rule_id,rule_version_id,device_id,org_id,space_id,alarm_type,alarm_level,point_code,alarm_value,
                   threshold_value,alarm_time,event_status,condition_status,event_fingerprint,active_fingerprint,
                   occurrence_count,first_occurrence_time,last_occurrence_time,last_sample_time,deal_status)
                VALUES ('PLATFORM',?,?,?,?,?,?,?,?,?,NOW(),'NEW','ACTIVE',?,?,1,NOW(),NOW(),?,0)
                ON DUPLICATE KEY UPDATE
                  occurrence_count=occurrence_count+IF(TIMESTAMPDIFF(SECOND,last_occurrence_time,NOW())>=60,1,0),
                  last_occurrence_time=IF(TIMESTAMPDIFF(SECOND,last_occurrence_time,NOW())>=60,NOW(),last_occurrence_time),
                  alarm_time=NOW(),alarm_value=VALUES(alarm_value),version=version+1
                """, ruleId, row.get("rule_version_id"), deviceId, row.get("org_id"), row.get("space_id"), row.get("alarm_type"),
                row.get("alarm_level"), row.get("point_code"), value,
                String.valueOf(row.get("freshness_seconds")) + "s", fingerprint, fingerprint, row.get("last_sample_time"));
        if (existing == null) {
            Long alarmId = jdbcTemplate.queryForObject("SELECT id FROM log_alarm WHERE active_fingerprint=?", Long.class, fingerprint);
            jdbcTemplate.update("""
                    INSERT INTO alarm_event_log (alarm_id,action,from_status,to_status,operator_name,content)
                    VALUES (?,'TRIGGER',NULL,'NEW','data-service',?)
                    """, alarmId, value + "，超过 " + row.get("freshness_seconds") + " 秒未收到有效数据");
        }
    }

    private long number(Object value) {
        return Long.parseLong(String.valueOf(value));
    }
}
