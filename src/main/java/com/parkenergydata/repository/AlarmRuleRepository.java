package com.parkenergydata.repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import com.parkenergydata.entity.AlarmRule;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AlarmRuleRepository {
    @Select("""
            SELECT id, rule_name, alarm_type, rule_scope, org_id, device_id, point_code,
                   compare_operator, threshold_value, threshold_min, threshold_max,
                   duration_seconds, alarm_level, enabled
            FROM alarm_rule
            WHERE enabled = 1
              AND (rule_scope = 1 OR (rule_scope = 2 AND org_id = #{orgId}) OR (rule_scope = 3 AND device_id = #{deviceId}))
            """)
    @ConstructorArgs({
            @Arg(column = "id", javaType = Long.class),
            @Arg(column = "rule_name", javaType = String.class),
            @Arg(column = "alarm_type", javaType = Integer.class),
            @Arg(column = "rule_scope", javaType = Integer.class),
            @Arg(column = "org_id", javaType = Long.class),
            @Arg(column = "device_id", javaType = Long.class),
            @Arg(column = "point_code", javaType = String.class),
            @Arg(column = "compare_operator", javaType = String.class),
            @Arg(column = "threshold_value", javaType = BigDecimal.class),
            @Arg(column = "threshold_min", javaType = BigDecimal.class),
            @Arg(column = "threshold_max", javaType = BigDecimal.class),
            @Arg(column = "duration_seconds", javaType = Integer.class),
            @Arg(column = "alarm_level", javaType = Integer.class),
            @Arg(column = "enabled", javaType = boolean.class)
    })
    List<AlarmRule> findEnabledForDevice(@Param("deviceId") Long deviceId, @Param("orgId") Long orgId);

    @Insert("""
            INSERT INTO log_alarm
              (rule_id, device_id, org_id, alarm_type, alarm_level, point_code,
               alarm_value, threshold_value, alarm_time, deal_status)
            VALUES (#{rule.id}, #{deviceId}, #{orgId}, #{rule.alarmType}, #{rule.alarmLevel}, #{rule.pointCode},
                    #{value}, #{threshold}, NOW(), 0)
            """)
    void insertAlarm(@Param("rule") AlarmRule rule, @Param("deviceId") Long deviceId,
                     @Param("orgId") Long orgId, @Param("value") String value,
                     @Param("threshold") String threshold);

    @Select("""
            <script>
            SELECT id, rule_id, device_id, org_id, alarm_type, alarm_level, point_code,
                   alarm_value, threshold_value, alarm_time, deal_status, deal_time, deal_user, deal_remark
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
