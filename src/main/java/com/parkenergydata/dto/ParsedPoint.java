package com.parkenergydata.dto;

import java.math.BigDecimal;

public record ParsedPoint(
        String pointCode,
        String dataType,
        String businessRole,
        boolean statEnabled,
        Object value,
        BigDecimal numericValue
) {
}
