package com.parkenergydata.dto;

public record RealtimePointValue(
        String pointCode,
        String pointName,
        Object value,
        String unit,
        String businessRole,
        String quality
) {
}
