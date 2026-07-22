package com.parkenergydata.parser;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import com.parkenergydata.dto.MeterPayload;
import com.parkenergydata.dto.ParsedPoint;
import com.parkenergydata.entity.DevPointDefinition;
import com.parkenergydata.entity.DevPointMapping;
import org.springframework.stereotype.Component;

@Component
public class JsonPointParser {
    private final ObjectMapper objectMapper;

    public JsonPointParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<ParsedPoint> parse(MeterPayload meter, Map<String, DevPointDefinition> definitions,
                                   Map<String, DevPointMapping> mappings) {
        JsonNode meterNode = objectMapper.valueToTree(meter);
        String json = meterNode.toString();
        List<ParsedPoint> points = new ArrayList<>();
        for (DevPointDefinition definition : definitions.values()) {
            DevPointMapping mapping = mappings.get(definition.pointCode());
            if (mapping == null) {
                continue;
            }
            Object raw = readValue(json, meterNode, definition.pointCode(), mapping);
            if (raw == null) {
                if (mapping.required()) {
                    throw new IllegalArgumentException("Required point missing: " + definition.pointCode());
                }
                continue;
            }
            Object converted = convert(raw, definition, mapping);
            BigDecimal numeric = toBigDecimal(converted);
            points.add(new ParsedPoint(definition.pointCode(), definition.dataType(), definition.businessRole(),
                    definition.statEnabled(), converted, numeric));
        }
        return points;
    }

    private Object readValue(String json, JsonNode meterNode, String pointCode, DevPointMapping mapping) {
        String sourcePath = mapping.sourcePath();
        if (sourcePath != null && !sourcePath.isBlank()) {
            Object value = readJsonPath(json, sourcePath);
            if (value != null) {
                return value;
            }
        }
        Object compatibility = readCompatibilityPath(json, pointCode);
        if (compatibility != null) {
            return compatibility;
        }
        JsonNode direct = meterNode.get(pointCode);
        return direct == null || direct.isMissingNode() || direct.isNull() ? null : jsonNodeToValue(direct);
    }

    private Object readCompatibilityPath(String json, String pointCode) {
        String camel = snakeToCamel(pointCode);
        for (String path : List.of("$.registers." + camel, "$.points." + pointCode, "$.points." + camel, "$." + pointCode, "$." + camel)) {
            Object value = readJsonPath(json, path);
            if (value != null) {
                return value;
            }
        }
        if ("data_quality".equals(pointCode)) {
            return readJsonPath(json, "$.quality");
        }
        return null;
    }

    private Object readJsonPath(String json, String path) {
        try {
            return JsonPath.read(json, path);
        } catch (PathNotFoundException | IllegalArgumentException ex) {
            return null;
        }
    }

    private Object convert(Object raw, DevPointDefinition definition, DevPointMapping mapping) {
        if ("STRING".equalsIgnoreCase(definition.dataType())) {
            return String.valueOf(raw);
        }
        if ("BOOLEAN".equalsIgnoreCase(definition.dataType())) {
            if (raw instanceof Boolean bool) {
                return bool;
            }
            return "1".equals(String.valueOf(raw)) || "true".equalsIgnoreCase(String.valueOf(raw));
        }
        BigDecimal value = toBigDecimal(raw);
        if (value == null) {
            return raw;
        }
        BigDecimal scale = mapping.scaleFactor() == null ? BigDecimal.ONE : mapping.scaleFactor();
        BigDecimal offset = mapping.offsetValue() == null ? BigDecimal.ZERO : mapping.offsetValue();
        return value.multiply(scale).add(offset);
    }

    private BigDecimal toBigDecimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Object jsonNodeToValue(JsonNode node) {
        if (node.isNumber()) {
            return node.decimalValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        return node.asText();
    }

    private String snakeToCamel(String value) {
        StringBuilder result = new StringBuilder();
        boolean upperNext = false;
        for (char ch : value.toCharArray()) {
            if (ch == '_') {
                upperNext = true;
            } else if (upperNext) {
                result.append(Character.toUpperCase(ch));
                upperNext = false;
            } else {
                result.append(ch);
            }
        }
        return result.toString();
    }
}
