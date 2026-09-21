package com.parkenergydata.entity;

import java.math.BigDecimal;

public record AlarmRule(
        Long id,
        Long versionId,
        String ruleName,
        Integer alarmType,
        Integer ruleScope,
        Long orgId,
        Long spaceId,
        Long deviceId,
        String pointCode,
        String compareOperator,
        BigDecimal thresholdValue,
        BigDecimal thresholdMin,
        BigDecimal thresholdMax,
        Integer durationSeconds,
        Integer alarmLevel,
        boolean enabled,
        boolean orgIncludeChildren,
        String evaluationMode,
        BigDecimal recoveryThresholdValue,
        Integer recoverySamples,
        Integer freshnessSeconds,
        Integer maxSampleGapSeconds,
        Integer evaluationWindowSamples,
        Integer requiredHits,
        Integer windowSeconds,
        Long protocolId,
        Long protocolPointId,
        String protocolKey
) {
    /** Compatibility constructor for tests and callers that still build a basic threshold rule. */
    public AlarmRule(Long id, String ruleName, Integer alarmType, Integer ruleScope, Long orgId, Long deviceId,
                     String pointCode, String compareOperator, BigDecimal thresholdValue, BigDecimal thresholdMin,
                     BigDecimal thresholdMax, Integer durationSeconds, Integer alarmLevel, boolean enabled) {
        this(id, null, ruleName, alarmType, ruleScope, orgId, null, deviceId, pointCode, compareOperator,
                thresholdValue, thresholdMin, thresholdMax, durationSeconds, alarmLevel, enabled, true,
                "THRESHOLD", null, 3, 900, 900, 5, 3, 300, null, null, null);
    }

    public boolean isProtocolPoint() {
        return protocolId != null && protocolPointId != null && protocolKey != null;
    }
}
