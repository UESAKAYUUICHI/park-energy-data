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
        JsonNode points,
        JsonNode payload
) {
    /**
     * Returns the canonical device data body while accepting the legacy
     * points/registers envelopes used by existing gateways.
     */
    public JsonNode normalizedPayload() {
        if (payload != null && !payload.isNull() && !payload.isMissingNode()) {
            return payload;
        }
        if (points != null && !points.isNull() && !points.isMissingNode()) {
            return points;
        }
        return registers;
    }
}
