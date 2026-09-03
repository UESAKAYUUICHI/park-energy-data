package com.parkenergydata.entity;

import java.math.BigDecimal;

public record DevPointMapping(
        Long id,
        Long deviceTypeId,
        String pointCode,
        String protocolType,
        String sourcePath,
        String functionCode,
        Integer registerAddress,
        Integer registerLength,
        String valueType,
        String byteOrder,
        BigDecimal scaleFactor,
        BigDecimal offsetValue,
        String expression,
        boolean required
) {
}
