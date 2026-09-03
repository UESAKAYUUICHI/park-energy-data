package com.parkenergydata.repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Time;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Read-only tariff lookup used by the data service when tagging meter increments. */
@Repository
public class TariffPlanRepository {
    private final JdbcTemplate jdbcTemplate;

    public TariffPlanRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<TariffPlan> findApplicablePlans(long deviceOrgId, LocalDate date) {
        List<Long> ancestors = ancestors(deviceOrgId);
        if (ancestors.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(ancestors.size(), "?"));
        List<Object> args = new ArrayList<>(ancestors);
        args.add(Date.valueOf(date));
        args.add(Date.valueOf(date));
        String sql = "SELECT id, plan_code, plan_name, org_id, apply_mode, version, priority, timezone"
                + " FROM billing_tariff_plan WHERE status = 'ACTIVE' AND org_id IN (" + placeholders + ")"
                + " AND effective_start_date <= ? AND (effective_end_date IS NULL OR effective_end_date >= ?)";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, args.toArray());
        Map<Long, Integer> distance = new HashMap<>();
        for (int i = 0; i < ancestors.size(); i++) {
            distance.put(ancestors.get(i), i);
        }
        return rows.stream().map(row -> new TariffPlan(
                        ((Number) row.get("id")).longValue(),
                        String.valueOf(row.get("plan_code")), String.valueOf(row.get("plan_name")),
                        ((Number) row.get("org_id")).longValue(), String.valueOf(row.get("apply_mode")), ((Number) row.get("version")).intValue(),
                        ((Number) row.get("priority")).intValue(), String.valueOf(row.get("timezone"))))
                .sorted(Comparator.comparingInt((TariffPlan plan) -> distance.getOrDefault(plan.orgId(), Integer.MAX_VALUE))
                        .thenComparing(TariffPlan::priority, Comparator.reverseOrder())
                        .thenComparing(TariffPlan::id))
                .filter(plan -> !"SELF".equalsIgnoreCase(plan.applyMode()) || plan.orgId() == deviceOrgId)
                .toList();
    }

    public List<TariffPeriod> periods(long planId, LocalDate date) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT period_code, period_name, day_type, season_code, season_start_md, season_end_md,
                       start_time, end_time, unit_price, sort
                FROM billing_tariff_period
                WHERE tariff_plan_id = ?
                ORDER BY sort, id
                """, planId);
        return rows.stream()
                .map(row -> new TariffPeriod(String.valueOf(row.get("period_code")), String.valueOf(row.get("period_name")),
                        String.valueOf(row.get("day_type")), String.valueOf(row.get("season_code")),
                        stringOrNull(row.get("season_start_md")), stringOrNull(row.get("season_end_md")),
                        time(row.get("start_time")), time(row.get("end_time")), decimal(row.get("unit_price")),
                        ((Number) row.get("sort")).intValue()))
                .filter(period -> period.matches(date))
                .toList();
    }

    private List<Long> ancestors(long orgId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT id, parent_id FROM dev_org");
        Map<Long, Long> parent = new HashMap<>();
        for (Map<String, Object> row : rows) {
            parent.put(((Number) row.get("id")).longValue(),
                    row.get("parent_id") == null ? null : ((Number) row.get("parent_id")).longValue());
        }
        Set<Long> chain = new LinkedHashSet<>();
        Long current = orgId;
        while (current != null && chain.add(current)) {
            current = parent.get(current);
        }
        return new ArrayList<>(chain);
    }

    private LocalTime time(Object value) {
        if (value instanceof Time sqlTime) return sqlTime.toLocalTime();
        return LocalTime.parse(String.valueOf(value));
    }

    private BigDecimal decimal(Object value) {
        return value instanceof BigDecimal result ? result : new BigDecimal(String.valueOf(value));
    }

    private String stringOrNull(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    public record TariffPlan(long id, String planCode, String planName, long orgId, String applyMode, int version, int priority, String timezone) { }


    public record TariffPeriod(String periodCode, String periodName, String dayType, String seasonCode,
                               String seasonStartMd, String seasonEndMd, LocalTime startTime, LocalTime endTime,
                               BigDecimal unitPrice, int sort) {
        boolean matches(LocalDate date) {
            String expectedDay = date.getDayOfWeek().getValue() >= 6 ? "WEEKEND" : "WORKDAY";
            boolean dayMatches = "ALL".equalsIgnoreCase(dayType) || expectedDay.equalsIgnoreCase(dayType);
            if (!dayMatches) return false;
            if ("ALL".equalsIgnoreCase(seasonCode) || seasonStartMd == null || seasonEndMd == null) return true;
            String monthDay = String.format("%02d-%02d", date.getMonthValue(), date.getDayOfMonth());
            return seasonStartMd.compareTo(seasonEndMd) <= 0
                    ? monthDay.compareTo(seasonStartMd) >= 0 && monthDay.compareTo(seasonEndMd) <= 0
                    : monthDay.compareTo(seasonStartMd) >= 0 || monthDay.compareTo(seasonEndMd) <= 0;
        }
    }
}
