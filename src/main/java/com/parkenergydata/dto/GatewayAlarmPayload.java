package com.parkenergydata.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GatewayAlarmPayload(
        String schemaVersion, String messageId, String gatewaySn, long timestamp, String type,
        String eventId, String action, String alarmType, String level, String deviceSn,
        String pointCode, String pointName, Double alarmValue, Double thresholdValue,
        String compareOperator, String message
) {}
