package com.parkenergydata.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record RealtimeDeviceSnapshot(
        Long deviceId,
        String deviceSn,
        Long gatewayId,
        Long orgId,
        Instant collectTime,
        Instant receiveTime,
        Long delaySeconds,
        String freshnessStatus,
        String qualityStatus,
        Map<String, Object> points,
        List<RealtimePointValue> pointDetails,
        Integer dataQuality
) {
}
