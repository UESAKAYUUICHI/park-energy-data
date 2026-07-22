package com.parkenergydata.dto;

import java.time.Instant;
import java.util.Map;

public record RealtimeDeviceSnapshot(
        Long deviceId,
        String deviceSn,
        Long gatewayId,
        Long orgId,
        Instant collectTime,
        Map<String, Object> points,
        Integer dataQuality
) {
}
