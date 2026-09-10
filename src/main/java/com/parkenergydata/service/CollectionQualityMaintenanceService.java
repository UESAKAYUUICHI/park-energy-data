package com.parkenergydata.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Refreshes today's collection expectations even when a device has stopped sending data entirely. */
@Service
public class CollectionQualityMaintenanceService {
    private final JdbcTemplate jdbcTemplate;

    public CollectionQualityMaintenanceService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Scheduled(fixedDelayString = "${park.data.quality-refresh-ms:60000}", initialDelay = 15000)
    @Transactional
    public void refreshToday() {
        jdbcTemplate.update("""
                INSERT INTO stats_collection_daily
                  (device_id, org_id, stat_date, expected_samples, received_samples, data_complete_rate,
                   longest_gap_seconds, first_collect_time, last_collect_time, quality_status)
                SELECT d.id, d.org_id, CURDATE(),
                       GREATEST(1, CEIL((TIMESTAMPDIFF(SECOND,
                         GREATEST(CAST(CURDATE() AS DATETIME),
                           COALESCE(CAST(d.quality_gate_start_date AS DATETIME), CAST(CURDATE() AS DATETIME))),
                         NOW()) + 1) / GREATEST(10, COALESCE(d.collect_interval_seconds, 300)))),
                       0, 0.00, 0, NULL, NULL, 'INCOMPLETE'
                FROM dev_device d
                WHERE d.status = 1 AND d.org_id IS NOT NULL
                  AND (d.quality_gate_start_date IS NULL OR d.quality_gate_start_date <= CURDATE())
                ON DUPLICATE KEY UPDATE
                  expected_samples = VALUES(expected_samples),
                  longest_gap_seconds = GREATEST(longest_gap_seconds,
                    CASE WHEN last_collect_time IS NULL THEN 0
                         ELSE TIMESTAMPDIFF(SECOND, last_collect_time, NOW()) END),
                  data_complete_rate = LEAST(100.00,
                    ROUND(received_samples * 100.00 / GREATEST(1, VALUES(expected_samples)), 2)),
                  quality_status = CASE
                    WHEN quality_status = 'ABNORMAL' THEN 'ABNORMAL'
                    WHEN received_samples * 100.00 / GREATEST(1, VALUES(expected_samples)) >
                           COALESCE((SELECT quality_threshold_pct FROM dev_device
                                     WHERE id = stats_collection_daily.device_id), 80.00)
                         AND (last_collect_time IS NULL OR TIMESTAMPDIFF(SECOND, last_collect_time, NOW())
                              <= GREATEST(10, COALESCE((SELECT collect_interval_seconds FROM dev_device
                                                       WHERE id = stats_collection_daily.device_id), 300)) * 3)
                    THEN 'NORMAL' ELSE 'INCOMPLETE' END
                """);
    }
}
