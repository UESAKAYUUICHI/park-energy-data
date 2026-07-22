package com.parkenergydata.entity;

public record DevPointDefinition(
        Long id,
        Long deviceTypeId,
        String pointCode,
        String pointName,
        String dataType,
        String unit,
        Integer precisionScale,
        String businessRole,
        boolean billable,
        boolean statEnabled,
        boolean enabled
) {
}
