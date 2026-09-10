package com.parkenergydata.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.databind.JsonNode;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MeterPayload(
        @JsonAlias("device_sn") String deviceSn,
        @JsonAlias("modbus_addr") Integer modbusAddr,
        @JsonAlias("channel_id") String channelId,
        @JsonAlias("profile_key") String profileKey,
        @JsonAlias("model_version") String modelVersion,
        @JsonAlias("config_revision") String configRevision,
        @JsonAlias("collect_time") Long collectTime,
        @JsonAlias("sample_interval_seconds") Integer sampleIntervalSeconds,
        Integer quality,
        JsonNode registers,
        JsonNode points,
        JsonNode payload
) {
    public MeterPayload(String deviceSn, Integer modbusAddr, Long collectTime, Integer quality,
                        JsonNode registers, JsonNode points, JsonNode payload) {
        this(deviceSn, modbusAddr, null, null, null, null, collectTime, null, quality, registers, points, payload);
    }

    public MeterPayload(String deviceSn, Integer modbusAddr, Long collectTime, Integer sampleIntervalSeconds,
                        Integer quality, JsonNode registers, JsonNode points, JsonNode payload) {
        this(deviceSn, modbusAddr, null, null, null, null, collectTime, sampleIntervalSeconds,
                quality, registers, points, payload);
    }

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
