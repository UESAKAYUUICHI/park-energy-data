package com.parkenergydata.repository;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import com.parkenergydata.entity.DevDevice;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Keeps one compact, auditable collection-health record for each device and day. */
@Repository
public class CollectionQualityRepository {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate jdbcTemplate;

    public CollectionQualityRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void recordAcceptedMeter(DevDevice device, Instant collectTime) {
        int interval = normalizedInterval(device.collectIntervalSeconds());
        LocalDate statDate = collectTime.atZone(ZONE).toLocalDate();
        long elapsedSeconds = Math.max(0L, collectTime.getEpochSecond() - statDate.atStartOfDay(ZONE).toEpochSecond());
        int expected = Math.max(1, (int) Math.ceil((elapsedSeconds + 1D) / interval));
        int maxGap = interval * 3;
        jdbcTemplate.update("""
                INSERT INTO stats_collection_daily
                  (device_id, org_id, stat_date, expected_samples, received_samples, data_complete_rate,
                   longest_gap_seconds, first_collect_time, last_collect_time, quality_status)
                VALUES (?, ?, ?, ?, 1, LEAST(100.00, ROUND(100.00 / ?, 2)), 0, ?, ?,
                   CASE WHEN 100.00 / ? >= ? THEN 'NORMAL' ELSE 'INCOMPLETE' END)
                ON DUPLICATE KEY UPDATE
                  expected_samples = GREATEST(expected_samples, VALUES(expected_samples)),
                  received_samples = received_samples + 1,
                  longest_gap_seconds = CASE
                    WHEN VALUES(last_collect_time) > last_collect_time THEN GREATEST(longest_gap_seconds,
                         TIMESTAMPDIFF(SECOND, last_collect_time, VALUES(last_collect_time)))
                    ELSE longest_gap_seconds END,
                  first_collect_time = LEAST(first_collect_time, VALUES(first_collect_time)),
                  last_collect_time = GREATEST(last_collect_time, VALUES(last_collect_time)),
                  data_complete_rate = LEAST(100.00, ROUND(received_samples * 100.00 / expected_samples, 2)),
                  quality_status = CASE
                    WHEN quality_status = 'ABNORMAL' THEN 'ABNORMAL'
                    WHEN longest_gap_seconds > ? THEN 'INCOMPLETE'
                    WHEN received_samples * 100.00 / expected_samples >= ? THEN 'NORMAL'
                    ELSE 'INCOMPLETE' END
                """, device.id(), device.orgId(), Date.valueOf(statDate), expected, expected,
                Timestamp.from(collectTime), Timestamp.from(collectTime), expected, threshold(device),
                maxGap, threshold(device));
    }

    public void markAccumulatedRollback(DevDevice device, LocalDate statDate) {
        int expected = Math.max(1, (int) Math.ceil(86_400D / normalizedInterval(device.collectIntervalSeconds())));
        jdbcTemplate.update("""
                INSERT INTO stats_collection_daily
                  (device_id, org_id, stat_date, expected_samples, received_samples,
                   data_complete_rate, longest_gap_seconds, quality_status)
                VALUES (?, ?, ?, ?, 0, 0.00, 0, 'ABNORMAL')
                ON DUPLICATE KEY UPDATE quality_status = 'ABNORMAL', update_time = CURRENT_TIMESTAMP
                """, device.id(), device.orgId(), Date.valueOf(statDate), expected);
    }

    private int normalizedInterval(Integer interval) {
        return interval == null ? 300 : Math.min(Math.max(interval, 10), 86_400);
    }

    private java.math.BigDecimal threshold(DevDevice device) {
        return device.qualityThresholdPct() == null ? java.math.BigDecimal.valueOf(95) : device.qualityThresholdPct();
    }
}
