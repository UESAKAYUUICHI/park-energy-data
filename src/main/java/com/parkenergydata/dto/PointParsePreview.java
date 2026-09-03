package com.parkenergydata.dto;

/** Human-readable point-level result used before publishing a new device mapping. */
public record PointParsePreview(
        String pointCode,
        String pointName,
        String unit,
        String status,
        Object rawValue,
        Object convertedValue,
        String errorCode,
        String errorMessage
) {
}
