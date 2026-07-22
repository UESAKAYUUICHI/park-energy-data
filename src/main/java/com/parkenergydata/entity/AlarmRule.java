package com.parkenergydata.entity;

import java.math.BigDecimal;

public record AlarmRule(
        Long id,
        String ruleName,
        Integer alarmType,
        Integer ruleScope,
        Long orgId,
        Long deviceId,
        String pointCode,
        String compareOperator,
        BigDecimal thresholdValue,
        BigDecimal thresholdMin,
        BigDecimal thresholdMax,
        Integer durationSeconds,
        Integer alarmLevel,
        boolean enabled
) {
}
