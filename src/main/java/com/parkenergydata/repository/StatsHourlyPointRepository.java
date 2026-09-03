package com.parkenergydata.repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface StatsHourlyPointRepository {
    @Insert("""
            INSERT INTO stats_hourly_point
              (device_id, device_type_id, org_id, point_code, stat_date, stat_hour,
               first_collect_time, last_collect_time, start_value, end_value, usage_value,
               max_value, min_value, avg_value, sample_count, expected_samples, data_complete_rate)
            VALUES (#{deviceId}, #{deviceTypeId}, #{orgId}, #{pointCode}, #{statDate}, #{statHour},
                    #{collectTime}, #{collectTime}, #{value}, #{value}, #{initialUsage},
                    #{value}, #{value}, #{value}, 1, #{expectedSamples},
                    LEAST(100.00, 100.00 / GREATEST(#{expectedSamples}, 1)))
            ON DUPLICATE KEY UPDATE
              start_value = IF(first_collect_time IS NULL OR VALUES(first_collect_time) < first_collect_time,
                               VALUES(start_value), start_value),
              first_collect_time = IF(first_collect_time IS NULL OR VALUES(first_collect_time) < first_collect_time,
                                      VALUES(first_collect_time), first_collect_time),
              end_value = IF(last_collect_time IS NULL OR VALUES(last_collect_time) >= last_collect_time,
                             VALUES(end_value), end_value),
              usage_value = IF(#{accumulated} = 1,
                               GREATEST(0, IF(last_collect_time IS NULL OR VALUES(last_collect_time) >= last_collect_time,
                                              VALUES(end_value), end_value) - start_value), NULL),
              last_collect_time = IF(last_collect_time IS NULL OR VALUES(last_collect_time) >= last_collect_time,
                                     VALUES(last_collect_time), last_collect_time),
              max_value = GREATEST(COALESCE(max_value, VALUES(max_value)), VALUES(max_value)),
              min_value = LEAST(COALESCE(min_value, VALUES(min_value)), VALUES(min_value)),
              avg_value = IF(avg_value IS NULL, VALUES(avg_value),
                             (avg_value * sample_count + VALUES(avg_value)) / (sample_count + 1)),
              expected_samples = GREATEST(expected_samples, VALUES(expected_samples)),
              data_complete_rate = LEAST(100.00,
                                          (sample_count + 1) * 100.00 /
                                          GREATEST(expected_samples, VALUES(expected_samples), 1)),
              sample_count = sample_count + 1,
              update_time = CURRENT_TIMESTAMP
            """)
    void upsert(@Param("deviceId") Long deviceId,
                @Param("deviceTypeId") Long deviceTypeId,
                @Param("orgId") Long orgId,
                @Param("pointCode") String pointCode,
                @Param("statDate") LocalDate statDate,
                @Param("statHour") int statHour,
                @Param("collectTime") Timestamp collectTime,
                @Param("value") BigDecimal value,
                @Param("initialUsage") BigDecimal initialUsage,
                @Param("expectedSamples") int expectedSamples,
                @Param("accumulated") int accumulated);

    @Delete("DELETE FROM stats_hourly_point WHERE device_id = #{deviceId} AND stat_date = #{statDate}")
    void deleteDeviceDate(@Param("deviceId") Long deviceId, @Param("statDate") LocalDate statDate);

    @Insert("""
            INSERT INTO stats_hourly_point
              (device_id, device_type_id, org_id, point_code, stat_date, stat_hour,
               first_collect_time, last_collect_time, start_value, end_value, usage_value,
               max_value, min_value, avg_value, sample_count, expected_samples, data_complete_rate)
            VALUES (#{deviceId}, #{deviceTypeId}, #{orgId}, #{pointCode}, #{statDate}, #{statHour},
                    #{firstCollectTime}, #{lastCollectTime}, #{startValue}, #{endValue}, #{usageValue},
                    #{maxValue}, #{minValue}, #{avgValue}, #{sampleCount}, #{expectedSamples}, #{completeRate})
            ON DUPLICATE KEY UPDATE
              first_collect_time = VALUES(first_collect_time),
              last_collect_time = VALUES(last_collect_time),
              start_value = VALUES(start_value),
              end_value = VALUES(end_value),
              usage_value = VALUES(usage_value),
              max_value = VALUES(max_value),
              min_value = VALUES(min_value),
              avg_value = VALUES(avg_value),
              sample_count = VALUES(sample_count),
              expected_samples = VALUES(expected_samples),
              data_complete_rate = VALUES(data_complete_rate),
              update_time = CURRENT_TIMESTAMP
            """)
    void replaceHourly(@Param("deviceId") Long deviceId,
                       @Param("deviceTypeId") Long deviceTypeId,
                       @Param("orgId") Long orgId,
                       @Param("pointCode") String pointCode,
                       @Param("statDate") LocalDate statDate,
                       @Param("statHour") int statHour,
                       @Param("firstCollectTime") Timestamp firstCollectTime,
                       @Param("lastCollectTime") Timestamp lastCollectTime,
                       @Param("startValue") BigDecimal startValue,
                       @Param("endValue") BigDecimal endValue,
                       @Param("usageValue") BigDecimal usageValue,
                       @Param("maxValue") BigDecimal maxValue,
                       @Param("minValue") BigDecimal minValue,
                       @Param("avgValue") BigDecimal avgValue,
                       @Param("sampleCount") int sampleCount,
                       @Param("expectedSamples") int expectedSamples,
                       @Param("completeRate") BigDecimal completeRate);

    @Select("""
            <script>
            SELECT id, device_id, device_type_id, org_id, point_code, stat_date, stat_hour,
                   first_collect_time, last_collect_time, start_value, end_value, usage_value,
                   max_value, min_value, avg_value, sample_count, expected_samples, data_complete_rate
            FROM stats_hourly_point
            WHERE 1 = 1
            <if test="deviceId != null">AND device_id = #{deviceId}</if>
            <if test="pointCode != null and pointCode != ''">AND point_code = #{pointCode}</if>
            <if test="startDate != null and startDate != ''">AND stat_date &gt;= #{startDate}</if>
            <if test="endDate != null and endDate != ''">AND stat_date &lt;= #{endDate}</if>
            ORDER BY stat_date DESC, stat_hour DESC, device_id, point_code
            LIMIT 2000
            </script>
            """)
    List<Map<String, Object>> findHourly(@Param("deviceId") Long deviceId,
                                         @Param("pointCode") String pointCode,
                                         @Param("startDate") String startDate,
                                         @Param("endDate") String endDate);
}
