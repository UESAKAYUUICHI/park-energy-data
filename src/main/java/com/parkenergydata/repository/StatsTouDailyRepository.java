package com.parkenergydata.repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class StatsTouDailyRepository {
    private final JdbcTemplate jdbcTemplate;

    public StatsTouDailyRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void add(long deviceId, long deviceTypeId, long orgId, String pointCode, java.time.LocalDate statDate,
                    long tariffPlanId, int tariffPlanVersion, String periodCode, BigDecimal usage) {
        jdbcTemplate.update("""
                INSERT INTO stats_tou_daily
                  (device_id, device_type_id, org_id, point_code, stat_date, tariff_plan_id, tariff_plan_version,
                   tariff_period_code, usage_value, sample_count, data_complete_rate, quality_status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1, 100.00, 'NORMAL')
                ON DUPLICATE KEY UPDATE usage_value = usage_value + VALUES(usage_value),
                    sample_count = sample_count + 1, data_complete_rate = 100.00, quality_status = 'NORMAL'
                """, deviceId, deviceTypeId, orgId, pointCode, Date.valueOf(statDate), tariffPlanId,
                tariffPlanVersion, periodCode, usage);
    }

    public void deleteDaily(long deviceId, java.time.LocalDate statDate) {
        jdbcTemplate.update("DELETE FROM stats_tou_daily WHERE device_id = ? AND stat_date = ?",
                deviceId, Date.valueOf(statDate));
    }

    public List<Map<String, Object>> find(Long deviceId, String pointCode, String startDate, String endDate) {
        StringBuilder sql = new StringBuilder("SELECT * FROM stats_tou_daily WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (deviceId != null) { sql.append(" AND device_id = ?"); args.add(deviceId); }
        if (pointCode != null && !pointCode.isBlank()) { sql.append(" AND point_code = ?"); args.add(pointCode.trim()); }
        if (startDate != null && !startDate.isBlank()) { sql.append(" AND stat_date >= ?"); args.add(Date.valueOf(startDate)); }
        if (endDate != null && !endDate.isBlank()) { sql.append(" AND stat_date <= ?"); args.add(Date.valueOf(endDate)); }
        sql.append(" ORDER BY stat_date, device_id, point_code, tariff_period_code");
        return jdbcTemplate.queryForList(sql.toString(), args.toArray());
    }
}
