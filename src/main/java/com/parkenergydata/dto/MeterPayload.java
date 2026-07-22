package com.parkenergydata.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.databind.JsonNode;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MeterPayload(
        @JsonAlias("device_sn") String deviceSn,
        @JsonAlias("modbus_addr") Integer modbusAddr,
        @JsonAlias("collect_time") Long collectTime,
        Integer quality,
        JsonNode registers,
        JsonNode points
) {
}
