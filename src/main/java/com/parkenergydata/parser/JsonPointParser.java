package com.parkenergydata.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.parkenergydata.dto.MeterPayload;
import com.parkenergydata.dto.ParsedPoint;
import com.parkenergydata.dto.PointParsePreview;
import com.parkenergydata.entity.DevPointDefinition;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Validates canonical gateway points. Fieldbus decoding and scaling belongs to the gateway. */
@Component
public class JsonPointParser {
    public JsonPointParser(com.fasterxml.jackson.databind.ObjectMapper ignored) { }

    public List<ParsedPoint> parse(MeterPayload meter, Map<String, DevPointDefinition> definitions) {
        JsonNode pointsNode = canonicalPoints(meter);
        List<ParsedPoint> result = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> fields = pointsNode.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String pointCode = entry.getKey().toUpperCase(Locale.ROOT);
            DevPointDefinition definition = definitions.get(pointCode);
            if (definition == null) throw new IllegalArgumentException("Unknown canonical point: " + entry.getKey());
            Object value = convert(entry.getValue(), definition);
            result.add(new ParsedPoint(definition.pointCode(), definition.dataType(), definition.businessRole(),
                    definition.statEnabled(), value, numeric(value)));
        }
        return result;
    }

    public List<PointParsePreview> preview(MeterPayload meter, Map<String, DevPointDefinition> definitions) {
        JsonNode pointsNode = canonicalPoints(meter);
        List<PointParsePreview> result = new ArrayList<>();
        for (DevPointDefinition definition : definitions.values()) {
            JsonNode raw = find(pointsNode, definition.pointCode());
            if (raw == null || raw.isNull()) {
                result.add(new PointParsePreview(definition.pointCode(), definition.pointName(), definition.unit(),
                        "SKIPPED", null, null, "NOT_REPORTED", "本次报文未上报该测点"));
                continue;
            }
            try {
                Object converted = convert(raw, definition);
                result.add(new PointParsePreview(definition.pointCode(), definition.pointName(), definition.unit(),
                        "SUCCESS", nodeValue(raw), converted, null, null));
            } catch (IllegalArgumentException exception) {
                result.add(new PointParsePreview(definition.pointCode(), definition.pointName(), definition.unit(),
                        "FAILED", nodeValue(raw), null, "TYPE_MISMATCH", exception.getMessage()));
            }
        }
        return result;
    }

    private JsonNode canonicalPoints(MeterPayload meter) {
        if (meter == null || meter.points() == null || !meter.points().isObject()) {
            throw new IllegalArgumentException("Canonical points object is required; raw registers are not accepted by Data");
        }
        return meter.points();
    }

    private JsonNode find(JsonNode points, String code) {
        JsonNode direct = points.get(code);
        if (direct != null) return direct;
        direct = points.get(code.toLowerCase(Locale.ROOT));
        if (direct != null) return direct;
        Iterator<Map.Entry<String, JsonNode>> fields = points.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            if (entry.getKey().equalsIgnoreCase(code)) return entry.getValue();
        }
        return null;
    }

    private Object convert(JsonNode node, DevPointDefinition definition) {
        String type = definition.dataType() == null ? "DOUBLE" : definition.dataType().toUpperCase(Locale.ROOT);
        return switch (type) {
            case "BOOLEAN" -> {
                if (node.isBoolean()) yield node.booleanValue();
                if (node.isIntegralNumber() && (node.intValue() == 0 || node.intValue() == 1)) yield node.intValue() == 1;
                throw new IllegalArgumentException(definition.pointCode() + " must be boolean or 0/1");
            }
            case "INT", "INTEGER", "LONG" -> {
                if (!node.isIntegralNumber()) throw new IllegalArgumentException(definition.pointCode() + " must be an integer");
                yield node.longValue();
            }
            case "DOUBLE", "DECIMAL", "NUMBER" -> {
                if (!node.isNumber()) throw new IllegalArgumentException(definition.pointCode() + " must be numeric");
                yield node.decimalValue();
            }
            case "STRING" -> {
                if (!node.isTextual()) throw new IllegalArgumentException(definition.pointCode() + " must be a string");
                yield node.textValue();
            }
            default -> throw new IllegalArgumentException("Unsupported point type: " + type);
        };
    }

    private Object nodeValue(JsonNode node) {
        if (node.isNumber()) return node.decimalValue();
        if (node.isBoolean()) return node.booleanValue();
        if (node.isTextual()) return node.textValue();
        return node.toString();
    }

    private BigDecimal numeric(Object value) {
        if (value instanceof BigDecimal decimal) return decimal;
        if (value instanceof Number number) return BigDecimal.valueOf(number.doubleValue());
        if (value instanceof Boolean bool) return bool ? BigDecimal.ONE : BigDecimal.ZERO;
        return null;
    }
}
