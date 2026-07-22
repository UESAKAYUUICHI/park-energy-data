package com.parkenergydata.dto;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AccessForwardMessage(
        @JsonAlias("raw_log_id") long rawLogId,
        @JsonAlias("message_id") String messageId,
        @JsonAlias("gateway_id") Long gatewayId,
        @JsonAlias("gateway_sn") String gatewaySn,
        @JsonAlias("payload_type") String payloadType,
        @JsonAlias("raw_payload") String rawPayload,
        @JsonAlias("received_at") Instant receivedAt
) {
}
