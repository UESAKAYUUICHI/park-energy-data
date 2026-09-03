package com.parkenergydata.service;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;

import com.parkenergydata.dto.ParsedPoint;
import com.parkenergydata.entity.DevDevice;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** One device packet writes all daily/hourly facts through JDBC batches instead of one round-trip per point. */
@Service
public class StatsPointBatchWriter {
    private final JdbcTemplate jdbcTemplate;

    public StatsPointBatchWriter(JdbcTemplate jdbcTemplate) { this.jdbcTemplate = jdbcTemplate; }

    public void upsertDaily(DevDevice device, LocalDate statDate, Timestamp collectTime, List<ParsedPoint> points) {
        List<ParsedPoint> candidates = eligible(points);
        if (candidates.isEmpty()) return;
        jdbcTemplate.batchUpdate(DAILY_SQL, new BatchPreparedStatementSetter() {
            public void setValues(PreparedStatement statement, int index) throws SQLException {
                ParsedPoint point = candidates.get(index);
                boolean accumulated = "TOTAL_ACCUMULATED".equalsIgnoreCase(point.businessRole());
                setCommon(statement, device, point, statDate, collectTime, accumulated, false, 0);
            }
            public int getBatchSize() { return candidates.size(); }
        });
    }

    public void upsertHourly(DevDevice device, LocalDate statDate, int statHour, Timestamp collectTime,
                             int expectedSamples, List<ParsedPoint> points) {
        List<ParsedPoint> candidates = eligible(points);
        if (candidates.isEmpty()) return;
        jdbcTemplate.batchUpdate(HOURLY_SQL, new BatchPreparedStatementSetter() {
            public void setValues(PreparedStatement statement, int index) throws SQLException {
                ParsedPoint point = candidates.get(index);
                boolean accumulated = "TOTAL_ACCUMULATED".equalsIgnoreCase(point.businessRole());
                setCommon(statement, device, point, statDate, collectTime, accumulated, true, expectedSamples);
                statement.setInt(9, statHour);
            }
            public int getBatchSize() { return candidates.size(); }
        });
    }

    private List<ParsedPoint> eligible(List<ParsedPoint> points) {
        return points.stream().filter(point -> point.statEnabled() && point.numericValue() != null).toList();
    }

    private void setCommon(PreparedStatement statement, DevDevice device, ParsedPoint point, LocalDate statDate,
                           Timestamp collectTime, boolean accumulated, boolean hourly, int expectedSamples) throws SQLException {
        statement.setLong(1, device.id());
        statement.setLong(2, device.deviceTypeId());
        statement.setLong(3, device.orgId());
        statement.setString(4, point.pointCode());
        statement.setObject(5, statDate);
        statement.setTimestamp(6, collectTime);
        statement.setTimestamp(7, collectTime);
        statement.setBigDecimal(8, point.numericValue());
        if (hourly) {
            // hourly SQL expects hour in position 9, then the numeric value is repeated at positions 10-13.
            statement.setBigDecimal(10, point.numericValue());
            statement.setBigDecimal(11, accumulated ? BigDecimal.ZERO : null);
            statement.setBigDecimal(12, point.numericValue());
            statement.setBigDecimal(13, point.numericValue());
            statement.setBigDecimal(14, point.numericValue());
            statement.setInt(15, 1);
            statement.setInt(16, expectedSamples);
            statement.setInt(17, expectedSamples);
            statement.setInt(18, accumulated ? 1 : 0);
        } else {
            statement.setBigDecimal(9, point.numericValue());
            statement.setBigDecimal(10, accumulated ? BigDecimal.ZERO : null);
            statement.setBigDecimal(11, point.numericValue());
            statement.setBigDecimal(12, point.numericValue());
            statement.setBigDecimal(13, point.numericValue());
            statement.setInt(14, accumulated ? 1 : 0);
        }
    }

    private static final String DAILY_SQL = """
            INSERT INTO stats_daily_point (device_id, device_type_id, org_id, point_code, stat_date,
              first_collect_time, last_collect_time, start_value, end_value, usage_value, max_value, min_value, avg_value, data_complete_rate, sample_count)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 100.00, 1)
            ON DUPLICATE KEY UPDATE
              start_value=IF(first_collect_time IS NULL OR VALUES(first_collect_time)<first_collect_time, VALUES(start_value), start_value),
              first_collect_time=IF(first_collect_time IS NULL OR VALUES(first_collect_time)<first_collect_time, VALUES(first_collect_time), first_collect_time),
              end_value=IF(last_collect_time IS NULL OR VALUES(last_collect_time)>=last_collect_time, VALUES(end_value), end_value),
              usage_value=IF(?=1, GREATEST(0, IF(last_collect_time IS NULL OR VALUES(last_collect_time)>=last_collect_time, VALUES(end_value), end_value)-COALESCE(start_value, VALUES(start_value))), usage_value),
              last_collect_time=IF(last_collect_time IS NULL OR VALUES(last_collect_time)>=last_collect_time, VALUES(last_collect_time), last_collect_time),
              max_value=GREATEST(COALESCE(max_value, VALUES(max_value)), VALUES(max_value)),
              min_value=LEAST(COALESCE(min_value, VALUES(min_value)), VALUES(min_value)),
              avg_value=IF(avg_value IS NULL, VALUES(avg_value), (avg_value*sample_count+VALUES(avg_value))/(sample_count+1)),
              sample_count=sample_count+1, update_time=CURRENT_TIMESTAMP
            """;

    private static final String HOURLY_SQL = """
            INSERT INTO stats_hourly_point (device_id, device_type_id, org_id, point_code, stat_date,
              first_collect_time, last_collect_time, start_value, stat_hour, end_value, usage_value, max_value, min_value, avg_value, sample_count, expected_samples, data_complete_rate)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, LEAST(100.00, 100.00/GREATEST(?, 1)))
            ON DUPLICATE KEY UPDATE
              start_value=IF(first_collect_time IS NULL OR VALUES(first_collect_time)<first_collect_time, VALUES(start_value), start_value),
              first_collect_time=IF(first_collect_time IS NULL OR VALUES(first_collect_time)<first_collect_time, VALUES(first_collect_time), first_collect_time),
              end_value=IF(last_collect_time IS NULL OR VALUES(last_collect_time)>=last_collect_time, VALUES(end_value), end_value),
              usage_value=IF(?=1, GREATEST(0, IF(last_collect_time IS NULL OR VALUES(last_collect_time)>=last_collect_time, VALUES(end_value), end_value)-start_value), NULL),
              last_collect_time=IF(last_collect_time IS NULL OR VALUES(last_collect_time)>=last_collect_time, VALUES(last_collect_time), last_collect_time),
              max_value=GREATEST(COALESCE(max_value, VALUES(max_value)), VALUES(max_value)),
              min_value=LEAST(COALESCE(min_value, VALUES(min_value)), VALUES(min_value)),
              avg_value=IF(avg_value IS NULL, VALUES(avg_value), (avg_value*sample_count+VALUES(avg_value))/(sample_count+1)),
              expected_samples=GREATEST(expected_samples, VALUES(expected_samples)),
              data_complete_rate=LEAST(100.00, (sample_count+1)*100.00/GREATEST(expected_samples, VALUES(expected_samples), 1)),
              sample_count=sample_count+1, update_time=CURRENT_TIMESTAMP
            """;
}
