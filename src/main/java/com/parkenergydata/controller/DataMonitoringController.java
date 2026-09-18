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
                SELECT t.id AS device_type_id,t.type_code,t.protocol_type,p.point_code,p.enabled,
                       b.id AS binding_id,f.field_code,f.document_address
                FROM dev_point_definition p
                JOIN dev_device_type t ON t.id=p.device_type_id
                LEFT JOIN dev_device_model_version v ON v.device_type_id=t.id
                LEFT JOIN dev_model_point_binding b ON b.model_version_id=v.id AND BINARY b.point_code=BINARY p.point_code
                LEFT JOIN dev_protocol_field f ON f.id=b.protocol_field_id
                WHERE p.enabled=1 AND t.enabled=1
                ORDER BY t.id,p.sort,p.id
                """);
        List<Map<String, Object>> issues = new java.util.ArrayList<>();
        for (Map<String, Object> row : rows) {
            String protocol = String.valueOf(row.get("protocol_type"));
            if (protocol.startsWith("MODBUS") && row.get("binding_id") == null) {
                issues.add(issue(row, "PROTOCOL_FIELD_BINDING_MISSING"));
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pointCount", rows.size());
        result.put("deviceTypeCount", rows.stream().map(row -> row.get("device_type_id")).distinct().count());
        result.put("valid", issues.isEmpty());
        result.put("profileBinding", "Modbus 产品测点必须绑定已发布协议字段；JSON 设备直接上报标准 points");
        result.put("issues", issues);
        return ApiResponse.ok(result);
    }

    private Map<String, Object> issue(Map<String, Object> row, String code) {
        Map<String, Object> issue = new LinkedHashMap<>();
        issue.put("deviceTypeId", row.get("device_type_id"));
        issue.put("deviceTypeCode", row.get("type_code"));
        issue.put("pointCode", row.get("point_code"));
        issue.put("protocolField", row.get("field_code"));
        issue.put("documentAddress", row.get("document_address"));
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

}
