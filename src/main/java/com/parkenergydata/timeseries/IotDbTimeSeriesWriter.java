package com.parkenergydata.timeseries;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.stream.Collectors;

import com.parkenergydata.config.ParkDataProperties;
import com.parkenergydata.dto.ParsedPoint;
import org.springframework.stereotype.Service;

@Service
public class IotDbTimeSeriesWriter implements TimeSeriesWriter {
    private final ParkDataProperties properties;

    public IotDbTimeSeriesWriter(ParkDataProperties properties) {
        this.properties = properties;
        loadDriver();
    }

    @Override
    public void writeDevicePoints(Long deviceId, Instant collectTime, List<ParsedPoint> points) {
        if (!properties.iotdb().enabled() || points.isEmpty()) {
            return;
        }
        String devicePath = devicePath(deviceId);
        String measurements = points.stream().map(ParsedPoint::pointCode).collect(Collectors.joining(", "));
        String values = points.stream().map(point -> formatValue(point.value())).collect(Collectors.joining(", "));
        String sql = "INSERT INTO " + devicePath + "(timestamp, " + measurements + ") VALUES("
                + collectTime.toEpochMilli() + ", " + values + ")";
        execute(sql);
    }

    @Override
    public List<Map<String, Object>> queryHistory(Long deviceId, String pointCode, String startTime, String endTime) {
        return queryHistory(deviceId, pointCode, startTime, endTime, 500);
    }

    @Override
    public List<Map<String, Object>> queryHistory(Long deviceId, String pointCode, String startTime, String endTime, int limit) {
        if (!properties.iotdb().enabled()) {
            return List.of();
        }
        String select = pointCode == null || pointCode.isBlank() ? "*" : pointCode.trim();
        StringBuilder sql = new StringBuilder("SELECT " + select + " FROM " + devicePath(deviceId));
        StringJoiner where = new StringJoiner(" AND ");
        if (startTime != null && !startTime.isBlank()) {
            where.add("time >= " + formatTime(startTime));
        }
        if (endTime != null && !endTime.isBlank()) {
            where.add("time <= " + formatTime(endTime));
        }
        String whereClause = where.toString();
        if (!whereClause.isBlank()) {
            sql.append(" WHERE ").append(whereClause);
        }
        sql.append(" LIMIT ").append(Math.max(1, Math.min(limit, 100000)));
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql.toString())) {
            ResultSetMetaData meta = rs.getMetaData();
            List<Map<String, Object>> rows = new ArrayList<>();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    row.put(meta.getColumnLabel(i), rs.getObject(i));
                }
                rows.add(row);
            }
            return rows;
        } catch (SQLException ex) {
            throw new IllegalStateException("IoTDB query failed", ex);
        }
    }

    private void execute(String sql) {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException ex) {
            throw new IllegalStateException("IoTDB write failed: " + sql, ex);
        }
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(properties.iotdb().jdbcUrl(),
                properties.iotdb().username(), properties.iotdb().password());
    }

    private void loadDriver() {
        if (!properties.iotdb().enabled()) {
            return;
        }
        String driverClassName = properties.iotdb().driverClassName();
        if (driverClassName == null || driverClassName.isBlank()) {
            driverClassName = "org.apache.iotdb.jdbc.IoTDBDriver";
        }
        try {
            Class.forName(driverClassName);
        } catch (ClassNotFoundException ex) {
            throw new IllegalStateException("IoTDB JDBC driver not found: " + driverClassName, ex);
        }
    }

    private String devicePath(Long deviceId) {
        String group = properties.iotdb().storageGroup();
        if (group == null || group.isBlank()) {
            group = "root.park_energy.device";
        }
        return group + ".d_" + deviceId;
    }

    private String formatValue(Object value) {
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        return "'" + String.valueOf(value).replace("'", "\\'") + "'";
    }

    private String formatTime(String input) {
        try {
            return String.valueOf(Instant.parse(input).toEpochMilli());
        } catch (Exception ex) {
            return "'" + input.replace("'", "\\'") + "'";
        }
    }
}
