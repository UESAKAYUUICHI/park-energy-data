package com.parkenergydata.repository;

import java.sql.Timestamp;
import java.time.Instant;

import com.parkenergydata.entity.DevDevice;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Persists five-minute raw-sample coverage separately from point validity. */
@Repository
public class CollectionWindowQualityRepository {
    private final JdbcTemplate jdbc;

    public CollectionWindowQualityRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void record(DevDevice device, Instant collectTime, Integer sampleIntervalSeconds, Integer reportWindowSeconds) {
        if (sampleIntervalSeconds == null || sampleIntervalSeconds <= 0) {
            return;
        }
        int interval = Math.max(1, sampleIntervalSeconds);
        int window = reportWindowSeconds == null ? 300 : Math.max(interval, reportWindowSeconds);
        long windowMillis = window * 1_000L;
        Instant start = Instant.ofEpochMilli(Math.floorDiv(collectTime.toEpochMilli(), windowMillis) * windowMillis);
        Instant end = start.plusSeconds(window);
        int expected = Math.max(1, window / interval);
        int claimed = jdbc.update("""
                INSERT IGNORE INTO stats_collection_window_sample
                  (device_id, collect_time, window_start, sample_interval_seconds)
                VALUES (?, ?, ?, ?)
                """, device.id(), Timestamp.from(collectTime), Timestamp.from(start), interval);
        if (claimed == 0) {
            return;
        }
        jdbc.update("""
                INSERT INTO stats_collection_window
                  (device_id,window_start,window_end,expected_samples,received_samples,data_complete_rate,first_collect_time,last_collect_time,quality_status)
                VALUES (?, ?, ?, ?, 1, ROUND(100.00 / ?, 2), ?, ?, 'INCOMPLETE')
                ON DUPLICATE KEY UPDATE
                  expected_samples=GREATEST(expected_samples,VALUES(expected_samples)),
                  received_samples=received_samples+1,
                  data_complete_rate=LEAST(100.00,ROUND((received_samples+1)*100.00/GREATEST(expected_samples,VALUES(expected_samples),1),2)),
                  first_collect_time=LEAST(first_collect_time,VALUES(first_collect_time)),
                  last_collect_time=GREATEST(last_collect_time,VALUES(last_collect_time)),
                  quality_status=CASE
                    WHEN (received_samples+1)*100.00/GREATEST(expected_samples,VALUES(expected_samples),1) > 80.00
                    THEN 'NORMAL' ELSE 'INCOMPLETE' END
                """, device.id(), Timestamp.from(start), Timestamp.from(end), expected, expected,
                Timestamp.from(collectTime), Timestamp.from(collectTime));
    }

    public java.util.List<java.util.Map<String, Object>> find(Long deviceId, Instant from, Instant to, int limit) {
        return jdbc.queryForList("""
                SELECT device_id AS deviceId, window_start AS windowStart, window_end AS windowEnd,
                       expected_samples AS expectedSamples, received_samples AS receivedSamples,
                       data_complete_rate AS dataCompleteRate, first_collect_time AS firstCollectTime,
                       last_collect_time AS lastCollectTime, quality_status AS qualityStatus, update_time AS updateTime
                FROM stats_collection_window
                WHERE device_id = ? AND window_start >= ? AND window_start < ?
                ORDER BY window_start DESC LIMIT ?
                """, deviceId, Timestamp.from(from), Timestamp.from(to), Math.max(1, Math.min(limit, 500)));
    }
}
