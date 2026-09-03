package com.parkenergydata.controller;

import com.parkenergydata.common.ApiResponse;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Operational checks and a database-backed contract report for every active device type. */
@RestController
@RequestMapping("/api/data")
public class DataMonitoringController {
    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final ConnectionFactory rabbit;

    public DataMonitoringController(JdbcTemplate jdbc, StringRedisTemplate redis, ConnectionFactory rabbit) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.rabbit = rabbit;
    }

    @GetMapping("/monitoring/health")
    public ApiResponse<Map<String, Object>> health() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("database", checkDatabase());
        result.put("redis", checkRedis());
        result.put("rabbitMq", checkRabbit());
        result.put("deadLetterCount", jdbc.queryForObject(
                "SELECT COUNT(*) FROM data_ingest_event WHERE status = 'DEAD_LETTER'", Long.class));
        result.put("processingCount", jdbc.queryForObject(
                "SELECT COUNT(*) FROM data_ingest_event WHERE status = 'PROCESSING'", Long.class));
        result.put("latestSuccessAt", jdbc.queryForObject(
                "SELECT MAX(processed_at) FROM data_ingest_event WHERE status = 'SUCCESS'", Object.class));
        return ApiResponse.ok(result);
    }

    @GetMapping("/contracts/meter-points")
    public ApiResponse<Map<String, Object>> meterPointContract() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT d.id AS device_type_id, d.type_code, p.point_code, p.source_path, p.required,
                       EXISTS(SELECT 1 FROM dev_point_definition f
                              WHERE f.device_type_id=p.device_type_id AND f.point_code=p.point_code AND f.enabled=1) AS definition_exists
                FROM dev_point_mapping p JOIN dev_device_type d ON d.id=p.device_type_id
                ORDER BY d.id,p.id
                """);
        List<Map<String, Object>> issues = new java.util.ArrayList<>();
        for (Map<String, Object> row : rows) {
            boolean definitionExists = truthy(row.get("definition_exists"));
            if (!definitionExists) issues.add(issue(row, "POINT_DEFINITION_MISSING"));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mappingCount", rows.size());
        result.put("deviceTypeCount", rows.stream().map(row -> row.get("device_type_id")).distinct().count());
        result.put("valid", issues.isEmpty());
        result.put("profileBinding", "每台网关电表在本地 UI 配置采集档案；新表型添加 profiles/*.toml 后选择对应 name 即可上报其声明测点");
        result.put("issues", issues);
        return ApiResponse.ok(result);
    }

    private Map<String, Object> issue(Map<String, Object> row, String code) {
        Map<String, Object> issue = new LinkedHashMap<>();
        issue.put("deviceTypeId", row.get("device_type_id"));
        issue.put("deviceTypeCode", row.get("type_code"));
        issue.put("pointCode", row.get("point_code"));
        issue.put("sourcePath", row.get("source_path"));
        issue.put("code", code);
        return issue;
    }

    private boolean checkDatabase() {
        try { return Integer.valueOf(1).equals(jdbc.queryForObject("SELECT 1", Integer.class)); }
        catch (RuntimeException ex) { return false; }
    }

    private boolean checkRedis() {
        try { return "PONG".equalsIgnoreCase(redis.getConnectionFactory().getConnection().ping()); }
        catch (RuntimeException ex) { return false; }
    }

    private boolean checkRabbit() {
        try (var connection = rabbit.createConnection()) { return connection.isOpen(); }
        catch (RuntimeException ex) { return false; }
    }

    private boolean truthy(Object value) {
        return Boolean.TRUE.equals(value) || "1".equals(String.valueOf(value)) || "true".equalsIgnoreCase(String.valueOf(value));
    }
}
