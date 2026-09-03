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
    private volatile boolean storageGroupReady;

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
        return queryHistory(deviceId, pointCode, startTime, endTime, 10000);
    }

    @Override
    public List<Map<String, Object>> queryHistory(Long deviceId, String pointCode, String startTime, String endTime, int limit) {
        if (!properties.iotdb().enabled()) {
            return List.of();
        }
        String select = pointCode == null || pointCode.isBlank() ? "*" : validatedPointCode(pointCode);
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
        sql.append(" ORDER BY TIME ASC LIMIT ").append(Math.max(1, Math.min(limit, 100000)));
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql.toString())) {
            ResultSetMetaData meta = rs.getMetaData();
            List<Map<String, Object>> rows = new ArrayList<>();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                Object time = rs.getObject(1);
                row.put("time", time);
                if (select.equals("*")) {
                    for (int i = 2; i <= meta.getColumnCount(); i++) {
                        row.put(measurementName(meta.getColumnLabel(i)), rs.getObject(i));
                    }
                } else {
                    row.put("pointCode", select);
                    row.put("value", meta.getColumnCount() >= 2 ? rs.getObject(2) : null);
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
            ensureStorageGroup(statement);
            statement.execute(sql);
        } catch (SQLException ex) {
            throw new IllegalStateException("IoTDB write failed: " + ex.getMessage(), ex);
        }
    }

    private synchronized void ensureStorageGroup(Statement statement) throws SQLException {
        if (storageGroupReady) {
            return;
        }
        if (!storageGroupExists(statement)) {
            statement.execute("CREATE DATABASE " + databasePath());
        }
        storageGroupReady = true;
    }

    private boolean storageGroupExists(Statement statement) throws SQLException {
        try (ResultSet databases = statement.executeQuery("SHOW DATABASES")) {
            while (databases.next()) {
                if (databasePath().equalsIgnoreCase(databases.getString(1))) {
                    return true;
                }
            }
        }
        return false;
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
        return storageGroup() + ".d_" + deviceId;
    }

    private String storageGroup() {
        String group = properties.iotdb().storageGroup();
        return group == null || group.isBlank() ? "root.park_energy.device" : group;
    }

    private String databasePath() {
        String[] nodes = storageGroup().split("\\.");
        if (nodes.length < 2 || !"root".equalsIgnoreCase(nodes[0])) {
            throw new IllegalStateException("IoTDB storage group must start with root.<database>: " + storageGroup());
        }
        return nodes[0] + "." + nodes[1];
    }

    private String validatedPointCode(String pointCode) {
        String value = pointCode.trim();
        if (!value.matches("[A-Za-z][A-Za-z0-9_]{0,63}")) {
            throw new IllegalArgumentException("Invalid pointCode: " + pointCode);
        }
        return value;
    }

    private String measurementName(String columnLabel) {
        int separator = columnLabel == null ? -1 : columnLabel.lastIndexOf('.');
        return separator >= 0 ? columnLabel.substring(separator + 1) : columnLabel;
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
            throw new IllegalArgumentException("Time must be ISO-8601 with timezone: " + input, ex);
        }
    }
}
