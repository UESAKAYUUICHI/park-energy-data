package com.parkenergydata.entity;

public record DevDevice(
        Long id,
        String deviceSn,
        String deviceName,
        Long gatewayId,
        Long orgId,
        Long deviceTypeId,
        String protocolAddr,
        Integer status
) {
}
