package com.parkenergydata.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface StatsDailyPointRepository {
    @Insert("""
            INSERT INTO stats_daily_point
              (device_id, device_type_id, org_id, point_code, stat_date,
               start_value, end_value, usage_value, max_value, min_value, avg_value, data_complete_rate)
            VALUES (#{deviceId}, #{deviceTypeId}, #{orgId}, #{pointCode}, #{statDate},
                    #{value}, #{value}, #{initialUsage}, #{value}, #{value}, #{value}, 100.00)
            ON DUPLICATE KEY UPDATE
              end_value = VALUES(end_value),
              usage_value = IF(#{accumulated} = 1, VALUES(end_value) - COALESCE(start_value, VALUES(end_value)), usage_value),
              max_value = GREATEST(COALESCE(max_value, VALUES(max_value)), VALUES(max_value)),
              min_value = LEAST(COALESCE(min_value, VALUES(min_value)), VALUES(min_value)),
              avg_value = IF(avg_value IS NULL, VALUES(avg_value), (avg_value + VALUES(avg_value)) / 2),
              update_time = CURRENT_TIMESTAMP
            """)
    void upsert(@Param("deviceId") Long deviceId,
                @Param("deviceTypeId") Long deviceTypeId,
                @Param("orgId") Long orgId,
                @Param("pointCode") String pointCode,
                @Param("statDate") LocalDate statDate,
                @Param("value") BigDecimal value,
                @Param("initialUsage") BigDecimal initialUsage,
                @Param("accumulated") int accumulated);

    @Insert("""
            INSERT INTO stats_daily_point
              (device_id, device_type_id, org_id, point_code, stat_date,
               start_value, end_value, usage_value, max_value, min_value, avg_value, data_complete_rate)
            VALUES (#{deviceId}, #{deviceTypeId}, #{orgId}, #{pointCode}, #{statDate},
                    #{startValue}, #{endValue}, #{usageValue}, #{maxValue}, #{minValue}, #{avgValue}, #{completeRate})
            ON DUPLICATE KEY UPDATE
              start_value = VALUES(start_value),
              end_value = VALUES(end_value),
              usage_value = VALUES(usage_value),
              max_value = VALUES(max_value),
              min_value = VALUES(min_value),
              avg_value = VALUES(avg_value),
              data_complete_rate = VALUES(data_complete_rate),
              update_time = CURRENT_TIMESTAMP
            """)
    void replaceDaily(@Param("deviceId") Long deviceId,
                      @Param("deviceTypeId") Long deviceTypeId,
                      @Param("orgId") Long orgId,
                      @Param("pointCode") String pointCode,
                      @Param("statDate") LocalDate statDate,
                      @Param("startValue") BigDecimal startValue,
                      @Param("endValue") BigDecimal endValue,
                      @Param("usageValue") BigDecimal usageValue,
                      @Param("maxValue") BigDecimal maxValue,
                      @Param("minValue") BigDecimal minValue,
                      @Param("avgValue") BigDecimal avgValue,
                      @Param("completeRate") BigDecimal completeRate);

    @Select("""
            <script>
            SELECT id, device_id, device_type_id, org_id, point_code, stat_date,
                   start_value, end_value, usage_value, max_value, min_value, avg_value, data_complete_rate
            FROM stats_daily_point
            WHERE 1 = 1
            <if test="deviceId != null">AND device_id = #{deviceId}</if>
            <if test="pointCode != null and pointCode != ''">AND point_code = #{pointCode}</if>
            <if test="startDate != null and startDate != ''">AND stat_date &gt;= #{startDate}</if>
            <if test="endDate != null and endDate != ''">AND stat_date &lt;= #{endDate}</if>
            ORDER BY stat_date DESC, device_id ASC, point_code ASC
            LIMIT 500
            </script>
            """)
    List<Map<String, Object>> findDaily(@Param("deviceId") Long deviceId,
                                        @Param("pointCode") String pointCode,
                                        @Param("startDate") String startDate,
                                        @Param("endDate") String endDate);
}
