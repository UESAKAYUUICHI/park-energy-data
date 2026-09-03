package com.parkenergydata.parser;

import java.math.BigDecimal;
import java.util.Map;
import java.util.LinkedHashMap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkenergydata.dto.MeterPayload;
import com.parkenergydata.entity.DevPointDefinition;
import com.parkenergydata.entity.DevPointMapping;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JsonPointParserTests {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JsonPointParser parser = new JsonPointParser(objectMapper);

    @Test
    void parsesJsonPathMappingAndAppliesScaleAndOffset() throws Exception {
        MeterPayload meter = new MeterPayload("METER-1", null, null, 0,
                objectMapper.readTree("{\"voltage\":220}"), null, null);
        DevPointDefinition definition = definition("voltage");
        DevPointMapping mapping = new DevPointMapping(1L, 1L, "voltage", "JSON",
                "$.registers.voltage", null, null, null, null, null,
                new BigDecimal("0.1"), BigDecimal.ONE, null, true);

        var points = parser.parse(meter, Map.of("voltage", definition), Map.of("voltage", mapping));

        assertThat(points).hasSize(1);
        assertThat(points.get(0).numericValue()).isEqualByComparingTo("23");
    }

    @Test
    void parsesModbusMappingByRegisterAddressFromStandardEnvelope() throws Exception {
        MeterPayload meter = new MeterPayload("METER-2", 1, null, 0,
                objectMapper.readTree("{\"40001\":231.5}"), null, null);
        DevPointDefinition definition = definition("line_voltage");
        DevPointMapping mapping = new DevPointMapping(2L, 1L, "line_voltage", "MODBUS_RTU",
                null, "03", 40001, 2, "FLOAT32", "ABCD",
                BigDecimal.ONE, BigDecimal.ZERO, null, true);

        var points = parser.parse(meter, Map.of("line_voltage", definition), Map.of("line_voltage", mapping));

        assertThat(points).hasSize(1);
        assertThat(points.get(0).numericValue()).isEqualByComparingTo("231.5");
    }

    @Test
    void decodesFloat32RegisterWordsAndAppliesConfiguredByteOrder() throws Exception {
        MeterPayload meter = new MeterPayload("METER-3", 1, null, 0,
                objectMapper.readTree("[0,17142]"), null, null);
        DevPointDefinition definition = definition("line_voltage");
        DevPointMapping mapping = new DevPointMapping(3L, 1L, "line_voltage", "MODBUS_RTU",
                null, "03", 40001, 2, "FLOAT32", "CDAB",
                BigDecimal.ONE, BigDecimal.ZERO, null, true);

        var points = parser.parse(meter, Map.of("line_voltage", definition), Map.of("line_voltage", mapping));

        assertThat(points).hasSize(1);
        assertThat(points.get(0).numericValue()).isEqualByComparingTo("123");
    }

    @Test
    void appliesRestrictedNumericExpressionBeforeScaleAndOffset() throws Exception {
        MeterPayload meter = new MeterPayload("METER-4", null, null, 0,
                null, objectMapper.readTree("{\"value\":12}"), null);
        DevPointDefinition definition = definition("custom_value");
        DevPointMapping mapping = new DevPointMapping(4L, 1L, "custom_value", "JSON",
                "$.points.value", null, null, null, null, null,
                new BigDecimal("0.5"), BigDecimal.ONE, "x * 2", true);

        var points = parser.parse(meter, Map.of("custom_value", definition), Map.of("custom_value", mapping));

        assertThat(points).hasSize(1);
        assertThat(points.get(0).numericValue()).isEqualByComparingTo("13");
    }

    @Test
    void previewsSuccessfulAndMissingRequiredPointsWithoutStoppingAtTheFirstFailure() throws Exception {
        MeterPayload meter = new MeterPayload("METER-5", null, null, 0,
                null, objectMapper.readTree("{\"voltage\":220}"), null);
        DevPointDefinition voltage = definition("VOLTAGE_A");
        DevPointDefinition current = definition("CURRENT_A");
        Map<String, DevPointMapping> mappings = Map.of(
                "VOLTAGE_A", new DevPointMapping(5L, 1L, "VOLTAGE_A", "JSON", "$.points.voltage",
                        null, null, null, null, null, BigDecimal.ONE, BigDecimal.ZERO, null, true),
                "CURRENT_A", new DevPointMapping(6L, 1L, "CURRENT_A", "JSON", "$.points.current",
                        null, null, null, null, null, BigDecimal.ONE, BigDecimal.ZERO, null, true));

        var preview = parser.preview(meter, Map.of("VOLTAGE_A", voltage, "CURRENT_A", current), mappings);

        assertThat(preview).extracting(item -> item.pointCode() + ":" + item.status())
                .containsExactlyInAnyOrder("VOLTAGE_A:SUCCESS", "CURRENT_A:FAILED");
        assertThat(preview).filteredOn(item -> "CURRENT_A".equals(item.pointCode()))
                .allMatch(item -> "REQUIRED_MISSING".equals(item.errorCode()));
    }

    @Test
    void parsesAllTwelvePd666CanonicalPoints() throws Exception {
        String[] codes = {
                "VOLTAGE_A", "VOLTAGE_B", "VOLTAGE_C", "CURRENT_A", "CURRENT_B", "CURRENT_C",
                "POWER_FACTOR_TOTAL", "FREQUENCY", "ACTIVE_POWER_TOTAL", "REACTIVE_POWER_TOTAL",
                "APPARENT_POWER_TOTAL", "FORWARD_ACTIVE_ENERGY"
        };
        String[] paths = {
                "voltage_a", "voltage_b", "voltage_c", "current_a", "current_b", "current_c",
                "power_factor_total", "frequency", "active_power_total", "reactive_power_total",
                "apparent_power_total", "forward_active_energy"
        };
        Map<String, DevPointDefinition> definitions = new LinkedHashMap<>();
        Map<String, DevPointMapping> mappings = new LinkedHashMap<>();
        for (int index = 0; index < codes.length; index++) {
            String code = codes[index];
            definitions.put(code, new DevPointDefinition((long) index + 1, 6L, code, code, "DOUBLE",
                    null, 3, "FORWARD_ACTIVE_ENERGY".equals(code) ? "TOTAL_ACCUMULATED" : "INSTANT_VALUE",
                    "FORWARD_ACTIVE_ENERGY".equals(code), true, true));
            mappings.put(code, new DevPointMapping((long) index + 1, 6L, code, "JSON",
                    "$.points." + paths[index], null, null, null, null, null,
                    BigDecimal.ONE, BigDecimal.ZERO, null, true));
        }
        MeterPayload meter = new MeterPayload("WZBC-LS2-M-325", 1, 1786092600000L, 0, null,
                objectMapper.readTree("""
                        {"voltage_a":221.4,"voltage_b":220.8,"voltage_c":221.1,
                         "current_a":2.18,"current_b":2.04,"current_c":1.96,
                         "power_factor_total":0.94,"frequency":50.02,
                         "active_power_total":1.31,"reactive_power_total":0.47,
                         "apparent_power_total":1.39,"forward_active_energy":12843.27}
                        """), null);

        var points = parser.parse(meter, definitions, mappings);

        assertThat(points).hasSize(12);
        assertThat(points).extracting(point -> point.pointCode())
                .containsExactly(codes);
        assertThat(points.get(11).numericValue()).isEqualByComparingTo("12843.27");
    }

    @Test
    void parsesCustomerPayloadWithoutDiscardingNestedFields() throws Exception {
        MeterPayload meter = new MeterPayload("CUSTOM-1", null, null, 0, null, null,
                objectMapper.readTree("""
                        {"telemetry":{"electrical":{"phaseA":{"voltage":2203}}}}
                        """));
        DevPointDefinition definition = definition("VOLTAGE_A");
        DevPointMapping mapping = new DevPointMapping(3L, 1L, "VOLTAGE_A", "JSON",
                "$.payload.telemetry.electrical.phaseA.voltage", null, null, null, null, null,
                new BigDecimal("0.1"), BigDecimal.ZERO, null, true);

        var points = parser.parse(meter, Map.of("VOLTAGE_A", definition), Map.of("VOLTAGE_A", mapping));

        assertThat(points).hasSize(1);
        assertThat(points.get(0).numericValue()).isEqualByComparingTo("220.3");
    }

    private DevPointDefinition definition(String code) {
        return new DevPointDefinition(1L, 1L, code, code, "DOUBLE", "V", 2,
                "INSTANT_VALUE", false, true, true);
    }
}
