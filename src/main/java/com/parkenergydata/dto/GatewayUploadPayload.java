package com.parkenergydata.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GatewayUploadPayload(
        @JsonAlias("message_id") String messageId,
        @JsonAlias("gateway_sn") String gatewaySn,
        Long timestamp,
        String type,
        @JsonAlias("sample_interval_seconds") Integer sampleIntervalSeconds,
        @JsonAlias("report_window_seconds") Integer reportWindowSeconds,
        List<MeterPayload> meters,
        @JsonAlias("schema_version") String schemaVersion
) {
    public GatewayUploadPayload(String messageId, String gatewaySn, Long timestamp, String type,
                                List<MeterPayload> meters, String schemaVersion) {
        this(messageId, gatewaySn, timestamp, type, null, null, meters, schemaVersion);
    }
}
