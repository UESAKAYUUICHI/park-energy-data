package com.parkenergydata.entity;

import java.math.BigDecimal;

public record DevPointMapping(
        Long id,
        Long deviceTypeId,
        String pointCode,
        String protocolType,
        String sourcePath,
        String valueType,
        BigDecimal scaleFactor,
        BigDecimal offsetValue,
        String expression,
        boolean required
) {
}
