package com.parkenergydata.repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.parkenergydata.entity.AlarmRule;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AlarmRuleRepository {
    @Select("""
            WITH RECURSIVE org_ancestors AS (
              SELECT id,parent_id FROM dev_org WHERE id=#{orgId}
              UNION ALL
              SELECT parent.id,parent.parent_id FROM dev_org parent JOIN org_ancestors child ON child.parent_id=parent.id
            )
            SELECT r.id, v.id AS version_id, v.rule_name, v.alarm_type, v.rule_scope, v.org_id, v.space_id, v.device_id,
                   v.point_code, v.compare_operator, v.threshold_value, v.threshold_min, v.threshold_max,
                   v.duration_seconds, v.alarm_level, r.enabled, v.org_include_children, v.evaluation_mode,
                   v.recovery_threshold_value, v.recovery_samples, v.freshness_seconds, v.max_sample_gap_seconds,
                   v.evaluation_window_samples, v.required_hits, v.window_seconds,
                   NULL AS protocol_id, NULL AS protocol_point_id, NULL AS protocol_key
            FROM alarm_rule r
            JOIN alarm_rule_version v ON v.id=COALESCE(
              (SELECT a.rule_version_id FROM log_alarm a
               WHERE a.rule_id=r.id AND a.device_id=#{deviceId} AND a.active_fingerprint IS NOT NULL
               ORDER BY a.id DESC LIMIT 1), r.published_version_id)
            WHERE (
              r.enabled=1 AND r.published_version_id IS NOT NULL AND
              (v.rule_scope=1
               OR (v.rule_scope=2 AND (v.org_id=#{orgId} OR (v.org_include_children=1 AND v.org_id IN (SELECT id FROM org_ancestors))))
               OR (v.rule_scope=3 AND v.device_id=#{deviceId})
               OR (v.rule_scope=4 AND v.space_id=(SELECT space_id FROM dev_device WHERE id=#{deviceId})))
              AND NOT EXISTS (
                SELECT 1 FROM alarm_protocol_device pd
                JOIN alarm_protocol p ON p.id=pd.protocol_id
                WHERE pd.device_id=#{deviceId} AND pd.enabled=1
                  AND p.lifecycle_status='PUBLISHED' AND p.enabled=1
              )
            ) OR EXISTS (
              SELECT 1 FROM log_alarm active_alarm
              WHERE active_alarm.rule_id=r.id AND active_alarm.device_id=#{deviceId} AND active_alarm.active_fingerprint IS NOT NULL
            )
            UNION ALL
            SELECT pp.id AS id, p.id AS version_id, pp.point_name AS rule_name, pp.alarm_type,
                   3 AS rule_scope, d.org_id, d.space_id, d.id AS device_id,
                   pp.point_code, pp.compare_operator, pp.threshold_value, pp.threshold_min, pp.threshold_max,
                   pp.duration_seconds, pp.alarm_level, pp.enabled, 1 AS org_include_children, pp.evaluation_mode,
                   pp.recovery_threshold_value, pp.recovery_samples, pp.freshness_seconds, pp.max_sample_gap_seconds,
                   pp.evaluation_window_samples, pp.required_hits, pp.window_seconds,
                   p.id AS protocol_id, pp.id AS protocol_point_id, p.protocol_key
            FROM alarm_protocol_device pd
            JOIN alarm_protocol p ON p.id=pd.protocol_id
            JOIN alarm_protocol_point pp ON pp.protocol_id=p.id
            JOIN dev_device d ON d.id=pd.device_id
            WHERE pd.device_id=#{deviceId} AND pd.enabled=1
              AND (p.lifecycle_status='PUBLISHED' AND p.enabled=1 AND pp.enabled=1
                   OR EXISTS (
                     SELECT 1 FROM log_alarm active_alarm
                     WHERE active_alarm.protocol_id=p.id
                       AND active_alarm.protocol_point_id=pp.id
                       AND active_alarm.device_id=#{deviceId}
                       AND active_alarm.condition_status='ACTIVE'
                   ))
            """)
    @ConstructorArgs({
            @Arg(column = "id", javaType = Long.class),
            @Arg(column = "version_id", javaType = Long.class),
            @Arg(column = "rule_name", javaType = String.class),
            @Arg(column = "alarm_type", javaType = Integer.class),
            @Arg(column = "rule_scope", javaType = Integer.class),
            @Arg(column = "org_id", javaType = Long.class),
            @Arg(column = "space_id", javaType = Long.class),
            @Arg(column = "device_id", javaType = Long.class),
            @Arg(column = "point_code", javaType = String.class),
            @Arg(column = "compare_operator", javaType = String.class),
            @Arg(column = "threshold_value", javaType = BigDecimal.class),
            @Arg(column = "threshold_min", javaType = BigDecimal.class),
            @Arg(column = "threshold_max", javaType = BigDecimal.class),
            @Arg(column = "duration_seconds", javaType = Integer.class),
            @Arg(column = "alarm_level", javaType = Integer.class),
            @Arg(column = "enabled", javaType = boolean.class),
            @Arg(column = "org_include_children", javaType = boolean.class),
            @Arg(column = "evaluation_mode", javaType = String.class),
            @Arg(column = "recovery_threshold_value", javaType = BigDecimal.class),
            @Arg(column = "recovery_samples", javaType = Integer.class),
            @Arg(column = "freshness_seconds", javaType = Integer.class),
            @Arg(column = "max_sample_gap_seconds", javaType = Integer.class),
            @Arg(column = "evaluation_window_samples", javaType = Integer.class),
            @Arg(column = "required_hits", javaType = Integer.class),
            @Arg(column = "window_seconds", javaType = Integer.class),
            @Arg(column = "protocol_id", javaType = Long.class),
            @Arg(column = "protocol_point_id", javaType = Long.class),
            @Arg(column = "protocol_key", javaType = String.class)
    })
    List<AlarmRule> findEnabledForDevice(@Param("deviceId") Long deviceId, @Param("orgId") Long orgId);

    @Insert("""
            <script>
            <choose>
            <when test="rule.protocolId != null">
            INSERT INTO log_alarm
              (alarm_source, rule_id, rule_version_id, protocol_id, protocol_point_id, device_id, org_id, space_id,
               alarm_type, alarm_level, point_code, alarm_value, threshold_value, alarm_time, event_status, condition_status,
               event_fingerprint, alarm_key, active_fingerprint, occurrence_count, first_occurrence_time, last_occurrence_time,
               last_sample_time, deal_status)
            VALUES ('PLATFORM', NULL, NULL, #{rule.protocolId}, #{rule.protocolPointId}, #{deviceId}, #{orgId}, #{rule.spaceId},
                    #{rule.alarmType}, #{rule.alarmLevel}, #{rule.pointCode}, #{value}, #{threshold}, #{sampleTime},
                    'NEW', 'ACTIVE',
                    CONCAT('PROTOCOL:',#{rule.protocolKey},':DEVICE:',#{deviceId},':POINT:',#{rule.pointCode}),
                    CONCAT('PROTOCOL:',#{rule.protocolKey},':DEVICE:',#{deviceId},':POINT:',#{rule.pointCode}),
                    CONCAT('PROTOCOL:',#{rule.protocolKey},':DEVICE:',#{deviceId},':POINT:',#{rule.pointCode}),
                    1, #{sampleTime}, #{sampleTime}, #{sampleTime}, 0)
            ON DUPLICATE KEY UPDATE
              protocol_id=VALUES(protocol_id), protocol_point_id=VALUES(protocol_point_id),
              occurrence_count=occurrence_count+1, last_occurrence_time=VALUES(last_occurrence_time),
              alarm_time=VALUES(alarm_time), alarm_value=VALUES(alarm_value), threshold_value=VALUES(threshold_value),
              last_sample_time=VALUES(last_sample_time),
              retrigger_count=retrigger_count+CASE WHEN condition_status='CLEARED' THEN 1 ELSE 0 END,
              event_status=CASE
                WHEN condition_status='CLEARED' AND process_time IS NOT NULL THEN 'IN_PROGRESS'
                WHEN condition_status='CLEARED' AND ack_time IS NOT NULL THEN 'ACKNOWLEDGED'
                WHEN condition_status='CLEARED' THEN 'NEW'
                WHEN suppress_until IS NOT NULL AND suppress_until&lt;=NOW() THEN 'NEW'
                ELSE event_status END,
              recovery_time=CASE WHEN condition_status='CLEARED' THEN NULL ELSE recovery_time END,
              recovery_value=CASE WHEN condition_status='CLEARED' THEN NULL ELSE recovery_value END,
              suppress_reason=CASE WHEN suppress_until IS NOT NULL AND suppress_until&lt;=NOW() THEN NULL ELSE suppress_reason END,
              suppress_until=CASE WHEN suppress_until IS NOT NULL AND suppress_until&lt;=NOW() THEN NULL ELSE suppress_until END,
              active_fingerprint=alarm_key, condition_status='ACTIVE', version=version+1
            </when>
            <otherwise>
            INSERT INTO log_alarm
              (alarm_source, rule_id, device_id, org_id, space_id, alarm_type, alarm_level, point_code,
               rule_version_id, alarm_value, threshold_value, alarm_time, event_status, condition_status, event_fingerprint, active_fingerprint,
               occurrence_count, first_occurrence_time, last_occurrence_time, last_sample_time, deal_status)
            VALUES ('PLATFORM', #{rule.id}, #{deviceId}, #{orgId}, #{rule.spaceId}, #{rule.alarmType}, #{rule.alarmLevel}, #{rule.pointCode}, #{rule.versionId},
                    #{value}, #{threshold}, #{sampleTime}, 'NEW', 'ACTIVE',
                    CONCAT('RULE:',#{rule.id},':DEVICE:',#{deviceId}), CONCAT('RULE:',#{rule.id},':DEVICE:',#{deviceId}),
                    1, #{sampleTime}, #{sampleTime}, #{sampleTime}, 0)
            ON DUPLICATE KEY UPDATE
              occurrence_count=occurrence_count+1,
              last_occurrence_time=VALUES(last_occurrence_time), alarm_time=VALUES(alarm_time), alarm_value=VALUES(alarm_value),
              last_sample_time=VALUES(last_sample_time),
              retrigger_count=retrigger_count+CASE WHEN condition_status='CLEARED' THEN 1 ELSE 0 END,
              event_status=CASE
                WHEN condition_status='CLEARED' AND process_time IS NOT NULL THEN 'IN_PROGRESS'
                WHEN condition_status='CLEARED' AND ack_time IS NOT NULL THEN 'ACKNOWLEDGED'
                WHEN condition_status='CLEARED' THEN 'NEW'
                WHEN suppress_until IS NOT NULL AND suppress_until&lt;=NOW() THEN 'NEW'
                ELSE event_status END,
              recovery_time=CASE WHEN condition_status='CLEARED' THEN NULL ELSE recovery_time END,
              recovery_value=CASE WHEN condition_status='CLEARED' THEN NULL ELSE recovery_value END,
              suppress_reason=CASE WHEN suppress_until IS NOT NULL AND suppress_until&lt;=NOW() THEN NULL ELSE suppress_reason END,
              suppress_until=CASE WHEN suppress_until IS NOT NULL AND suppress_until&lt;=NOW() THEN NULL ELSE suppress_until END,
              condition_status='ACTIVE',
              version=version+1
            </otherwise>
            </choose>
            </script>
            """)
    int upsertAlarm(@Param("rule") AlarmRule rule, @Param("deviceId") Long deviceId,
                    @Param("orgId") Long orgId, @Param("value") String value,
                    @Param("threshold") String threshold, @Param("sampleTime") Instant sampleTime);

    @Select("""
            <script>
            SELECT id FROM log_alarm
            WHERE
            <choose>
              <when test="rule.protocolId != null">
                alarm_key=CONCAT('PROTOCOL:',#{rule.protocolKey},':DEVICE:',#{deviceId},':POINT:',#{rule.pointCode})
              </when>
              <otherwise>
                active_fingerprint=CONCAT('RULE:',#{rule.id},':DEVICE:',#{deviceId})
              </otherwise>
            </choose>
            ORDER BY id DESC LIMIT 1
            </script>
            """)
    Long findAlarmId(@Param("rule") AlarmRule rule, @Param("deviceId") Long deviceId);

    @Select("SELECT event_status FROM log_alarm WHERE id=#{alarmId}")
    String findAlarmStatus(@Param("alarmId") Long alarmId);

    @Select("SELECT last_sample_time FROM log_alarm WHERE id=#{alarmId}")
    Instant findAlarmLastSampleTime(@Param("alarmId") Long alarmId);

    @Update("""
            UPDATE log_alarm SET event_status='RECOVERED', condition_status='CLEARED', recovery_time=#{sampleTime},
              recovery_value=#{value}, last_sample_time=#{sampleTime}, version=version+1
            WHERE id=#{alarmId} AND condition_status='ACTIVE'
              AND event_status IN ('NEW','ACKNOWLEDGED','IN_PROGRESS','SUPPRESSED')
            """)
    int recoverAlarm(@Param("alarmId") Long alarmId, @Param("value") String value,
                     @Param("sampleTime") Instant sampleTime);

    @Insert("""
            INSERT INTO alarm_event_log
              (alarm_id,action,from_status,to_status,operator_name,content)
            VALUES (#{alarmId},#{action},#{fromStatus},#{toStatus},'data-service',#{content})
            """)
    void insertEventLog(@Param("alarmId") Long alarmId, @Param("action") String action,
                        @Param("fromStatus") String fromStatus, @Param("toStatus") String toStatus,
                        @Param("content") String content);

    @Select("""
            <script>
            SELECT id, rule_id, device_id, org_id, space_id, alarm_type, alarm_level, point_code,
                   alarm_value, threshold_value, alarm_time, event_status, occurrence_count,
                   first_occurrence_time, last_occurrence_time, recovery_time,
                   deal_status, deal_time, deal_user, deal_remark
            FROM log_alarm
            WHERE 1 = 1
            <if test="deviceId != null">AND device_id = #{deviceId}</if>
            <if test="dealStatus != null">AND deal_status = #{dealStatus}</if>
            <if test="startTime != null and startTime != ''">AND alarm_time &gt;= #{startTime}</if>
            <if test="endTime != null and endTime != ''">AND alarm_time &lt;= #{endTime}</if>
            ORDER BY alarm_time DESC
            LIMIT 200
            </script>
            """)
    List<Map<String, Object>> findAlarms(@Param("deviceId") Long deviceId,
                                         @Param("dealStatus") Integer dealStatus,
                                         @Param("startTime") String startTime,
                                         @Param("endTime") String endTime);
}
