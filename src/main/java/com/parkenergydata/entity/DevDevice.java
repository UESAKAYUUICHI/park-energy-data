package com.parkenergydata.entity;

public record DevDevice(
        Long id,
        String deviceSn,
        String deviceName,
        Long gatewayId,
        Long orgId,
        Long spaceId,
        Long deviceTypeId,
        String protocolAddr,
        Integer status,
        boolean settlementEnabled,
        String meterRole,
        Integer collectIntervalSeconds,
        java.math.BigDecimal qualityThresholdPct
) {
    /** Compatibility constructor for callers that do not need the deployment space. */
    public DevDevice(Long id, String deviceSn, String deviceName, Long gatewayId, Long orgId,
                     Long deviceTypeId, String protocolAddr, Integer status, boolean settlementEnabled,
                     String meterRole, Integer collectIntervalSeconds, java.math.BigDecimal qualityThresholdPct) {
        this(id, deviceSn, deviceName, gatewayId, orgId, null, deviceTypeId, protocolAddr, status,
                settlementEnabled, meterRole, collectIntervalSeconds, qualityThresholdPct);
    }
}
