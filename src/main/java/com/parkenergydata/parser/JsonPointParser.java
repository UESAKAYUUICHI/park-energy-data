package com.parkenergydata.parser;

import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import com.parkenergydata.dto.MeterPayload;
import com.parkenergydata.dto.ParsedPoint;
import com.parkenergydata.dto.PointParsePreview;
import com.parkenergydata.entity.DevPointDefinition;
import com.parkenergydata.entity.DevPointMapping;
import org.springframework.stereotype.Component;

@Component
public class JsonPointParser {
    private static final Pattern SAFE_EXPRESSION = Pattern.compile("(?i)^x\\s*([+\\-*/])\\s*(-?(?:\\d+(?:\\.\\d*)?|\\.\\d+))$");
    private final ObjectMapper objectMapper;

    public JsonPointParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<ParsedPoint> parse(MeterPayload meter, Map<String, DevPointDefinition> definitions,
                                   Map<String, DevPointMapping> mappings) {
        JsonNode meterNode = objectMapper.valueToTree(meter);
        JsonNode normalizedPayload = meter.normalizedPayload();
        if (meterNode instanceof ObjectNode objectNode && normalizedPayload != null
                && !normalizedPayload.isNull() && !normalizedPayload.isMissingNode()) {
            objectNode.set("payload", normalizedPayload);
        }
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

    public List<PointParsePreview> preview(MeterPayload meter, Map<String, DevPointDefinition> definitions,
                                           Map<String, DevPointMapping> mappings) {
        JsonNode meterNode = objectMapper.valueToTree(meter);
        JsonNode normalizedPayload = meter == null ? null : meter.normalizedPayload();
        if (meterNode instanceof ObjectNode objectNode && normalizedPayload != null
                && !normalizedPayload.isNull() && !normalizedPayload.isMissingNode()) {
            objectNode.set("payload", normalizedPayload);
        }
        String json = meterNode.toString();
        List<PointParsePreview> previews = new ArrayList<>();
        for (DevPointDefinition definition : definitions.values()) {
            DevPointMapping mapping = mappings.get(definition.pointCode());
            if (mapping == null) {
                previews.add(preview(definition, "SKIPPED", null, null, "MAPPING_MISSING", "未配置测点映射"));
                continue;
            }
            try {
                Object raw = readValue(json, meterNode, definition.pointCode(), mapping);
                if (raw == null) {
                    String status = mapping.required() ? "FAILED" : "SKIPPED";
                    previews.add(preview(definition, status, null, null,
                            mapping.required() ? "REQUIRED_MISSING" : "OPTIONAL_MISSING",
                            mapping.required() ? "缺少必填测点" : "可选测点未上报"));
                    continue;
                }
                Object converted = convert(raw, definition, mapping);
                previews.add(preview(definition, "SUCCESS", raw, converted, null, null));
            } catch (RuntimeException exception) {
                previews.add(preview(definition, "FAILED", null, null, "CONVERSION_FAILED", exception.getMessage()));
            }
        }
        return previews;
    }

    private PointParsePreview preview(DevPointDefinition definition, String status, Object raw, Object converted,
                                      String errorCode, String errorMessage) {
        return new PointParsePreview(definition.pointCode(), definition.pointName(), definition.unit(), status,
                raw, converted, errorCode, errorMessage);
    }

    private Object readValue(String json, JsonNode meterNode, String pointCode, DevPointMapping mapping) {
        String sourcePath = mapping.sourcePath();
        if (sourcePath != null && !sourcePath.isBlank()) {
            Object value = readJsonPath(json, sourcePath);
            if (value != null) {
                return value;
            }
        }
        if (mapping.protocolType() != null && mapping.protocolType().toUpperCase().startsWith("MODBUS")) {
            Object registerValue = readModbusRegister(meterNode, mapping);
            if (registerValue != null) return registerValue;
        }
        Object compatibility = readCompatibilityPath(json, pointCode);
        if (compatibility != null) {
            return compatibility;
        }
        JsonNode direct = meterNode.get(pointCode);
        return direct == null || direct.isMissingNode() || direct.isNull() ? null : jsonNodeToValue(direct);
    }

    private Object readModbusRegister(JsonNode meterNode, DevPointMapping mapping) {
        JsonNode registers = meterNode.get("registers");
        if (registers == null || registers.isNull() || mapping.registerAddress() == null) return null;
        int address = mapping.registerAddress();
        int wordCount = expectedRegisterWords(mapping);
        if (registers.isArray()) {
            int start = arrayRegisterStart(address, registers.size(), wordCount);
            if (start < 0) return null;
            return wordsFromArray(registers, start, wordCount);
        }
        JsonNode value = registers.path(String.valueOf(address));
        if (value.isMissingNode() || value.isNull()) return null;
        if (value.isArray()) return wordsFromArray(value, 0, wordCount);
        if (wordCount > 1 && value.isIntegralNumber()) {
            List<Long> words = wordsFromObject(registers, address, wordCount);
            if (words.size() == wordCount) return words;
        }
        return jsonNodeToValue(value);
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
        if (raw instanceof List<?> words) {
            value = decodeRegisterWords(words, mapping);
        }
        if (value == null) {
            return raw;
        }
        value = applySafeExpression(value, mapping.expression());
        BigDecimal scale = mapping.scaleFactor() == null ? BigDecimal.ONE : mapping.scaleFactor();
        BigDecimal offset = mapping.offsetValue() == null ? BigDecimal.ZERO : mapping.offsetValue();
        return value.multiply(scale).add(offset);
    }

    private int expectedRegisterWords(DevPointMapping mapping) {
        String valueType = mapping.valueType() == null ? "" : mapping.valueType().toUpperCase();
        int typed = switch (valueType) {
            case "INT16", "UINT16" -> 1;
            case "INT32", "UINT32", "FLOAT32" -> 2;
            case "INT64", "UINT64", "DOUBLE", "FLOAT64" -> 4;
            default -> 1;
        };
        return Math.max(typed, mapping.registerLength() == null ? 1 : mapping.registerLength());
    }

    private int arrayRegisterStart(int address, int size, int wordCount) {
        if (address >= 0 && address + wordCount <= size) return address;
        int modbusOffset = address - 40001;
        return modbusOffset >= 0 && modbusOffset + wordCount <= size ? modbusOffset : -1;
    }

    private List<Long> wordsFromArray(JsonNode values, int start, int wordCount) {
        List<Long> words = new ArrayList<>();
        for (int index = start; index < start + wordCount && index < values.size(); index++) {
            Long word = word(values.get(index));
            if (word == null) return List.of();
            words.add(word);
        }
        return words;
    }

    private List<Long> wordsFromObject(JsonNode values, int address, int wordCount) {
        List<Long> words = new ArrayList<>();
        for (int offset = 0; offset < wordCount; offset++) {
            Long word = word(values.get(String.valueOf(address + offset)));
            if (word == null) return List.of();
            words.add(word);
        }
        return words;
    }

    private Long word(JsonNode node) {
        if (node == null || node.isNull() || !node.isIntegralNumber()) return null;
        long value = node.longValue();
        return value >= 0 && value <= 0xFFFFL ? value : null;
    }

    private BigDecimal decodeRegisterWords(List<?> values, DevPointMapping mapping) {
        int expectedWords = expectedRegisterWords(mapping);
        if (values.size() < expectedWords) {
            throw new IllegalArgumentException("Modbus register length is insufficient for " + mapping.pointCode());
        }
        byte[] bytes = new byte[expectedWords * 2];
        for (int index = 0; index < expectedWords; index++) {
            Long word = toWord(values.get(index));
            if (word == null) throw new IllegalArgumentException("Invalid Modbus register value for " + mapping.pointCode());
            bytes[index * 2] = (byte) ((word >> 8) & 0xFF);
            bytes[index * 2 + 1] = (byte) (word & 0xFF);
        }
        bytes = applyByteOrder(bytes, mapping.byteOrder());
        String valueType = mapping.valueType() == null ? "UINT16" : mapping.valueType().toUpperCase();
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        return switch (valueType) {
            case "INT16" -> BigDecimal.valueOf(buffer.getShort());
            case "UINT16" -> BigDecimal.valueOf(Short.toUnsignedInt(buffer.getShort()));
            case "INT32" -> BigDecimal.valueOf(buffer.getInt());
            case "UINT32" -> BigDecimal.valueOf(Integer.toUnsignedLong(buffer.getInt()));
            case "FLOAT32" -> BigDecimal.valueOf(buffer.getFloat());
            case "INT64" -> BigDecimal.valueOf(buffer.getLong());
            case "UINT64" -> new BigDecimal(Long.toUnsignedString(buffer.getLong()));
            case "DOUBLE", "FLOAT64" -> BigDecimal.valueOf(buffer.getDouble());
            default -> throw new IllegalArgumentException("Unsupported Modbus value type: " + valueType);
        };
    }

    private Long toWord(Object value) {
        if (value instanceof Number number) {
            long result = number.longValue();
            return result >= 0 && result <= 0xFFFFL ? result : null;
        }
        try {
            long result = Long.parseLong(String.valueOf(value));
            return result >= 0 && result <= 0xFFFFL ? result : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private byte[] applyByteOrder(byte[] source, String byteOrder) {
        if (byteOrder == null || byteOrder.isBlank()) return source;
        String order = byteOrder.trim().toUpperCase();
        if (order.length() != source.length) {
            throw new IllegalArgumentException("Byte order length does not match register length: " + order);
        }
        byte[] result = new byte[source.length];
        for (int index = 0; index < order.length(); index++) {
            char marker = order.charAt(index);
            int sourceIndex = marker - 'A';
            if (sourceIndex < 0 || sourceIndex >= source.length) {
                throw new IllegalArgumentException("Invalid byte order: " + order);
            }
            result[index] = source[sourceIndex];
        }
        return result;
    }

    private BigDecimal applySafeExpression(BigDecimal value, String expression) {
        if (expression == null || expression.isBlank() || "x".equalsIgnoreCase(expression.trim())) return value;
        Matcher matcher = SAFE_EXPRESSION.matcher(expression.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Only one safe x + - * / numeric expression is supported");
        }
        BigDecimal operand = new BigDecimal(matcher.group(2));
        return switch (matcher.group(1)) {
            case "+" -> value.add(operand);
            case "-" -> value.subtract(operand);
            case "*" -> value.multiply(operand);
            case "/" -> {
                if (operand.compareTo(BigDecimal.ZERO) == 0) {
                    throw new IllegalArgumentException("Expression cannot divide by zero");
                }
                yield value.divide(operand, MathContext.DECIMAL64);
            }
            default -> throw new IllegalArgumentException("Unsupported safe expression");
        };
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
