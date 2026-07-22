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
        List<MeterPayload> meters
) {
}
