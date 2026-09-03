package com.parkenergydata.repository;

import java.util.Optional;
import java.util.List;

import com.parkenergydata.entity.DevDevice;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DeviceRepository {
    @Select("""
            SELECT id, device_sn, device_name, gateway_id, org_id, space_id, device_type_id, protocol_addr, status,
                   settlement_enabled, meter_role, collect_interval_seconds, quality_threshold_pct
            FROM dev_device
            WHERE gateway_id = #{gatewayId}
              AND device_sn = CAST(#{deviceSn} AS CHAR CHARACTER SET utf8mb4) COLLATE utf8mb4_general_ci
              AND status = 1
            LIMIT 1
            """)
    @ConstructorArgs({
            @Arg(column = "id", javaType = Long.class),
            @Arg(column = "device_sn", javaType = String.class),
            @Arg(column = "device_name", javaType = String.class),
            @Arg(column = "gateway_id", javaType = Long.class),
            @Arg(column = "org_id", javaType = Long.class),
            @Arg(column = "space_id", javaType = Long.class),
            @Arg(column = "device_type_id", javaType = Long.class),
            @Arg(column = "protocol_addr", javaType = String.class),
            @Arg(column = "status", javaType = Integer.class),
            @Arg(column = "settlement_enabled", javaType = boolean.class),
            @Arg(column = "meter_role", javaType = String.class),
            @Arg(column = "collect_interval_seconds", javaType = Integer.class),
            @Arg(column = "quality_threshold_pct", javaType = java.math.BigDecimal.class)
    })
    Optional<DevDevice> findEnabledByGatewayAndSn(@Param("gatewayId") Long gatewayId, @Param("deviceSn") String deviceSn);

    @Select("""
            <script>
            SELECT id, device_sn, device_name, gateway_id, org_id, space_id, device_type_id, protocol_addr, status,
                   settlement_enabled, meter_role, collect_interval_seconds, quality_threshold_pct
            FROM dev_device
            WHERE status = 1
            <if test="deviceId != null">AND id = #{deviceId}</if>
            ORDER BY id
            </script>
            """)
    @ConstructorArgs({
            @Arg(column = "id", javaType = Long.class),
            @Arg(column = "device_sn", javaType = String.class),
            @Arg(column = "device_name", javaType = String.class),
            @Arg(column = "gateway_id", javaType = Long.class),
            @Arg(column = "org_id", javaType = Long.class),
            @Arg(column = "space_id", javaType = Long.class),
            @Arg(column = "device_type_id", javaType = Long.class),
            @Arg(column = "protocol_addr", javaType = String.class),
            @Arg(column = "status", javaType = Integer.class),
            @Arg(column = "settlement_enabled", javaType = boolean.class),
            @Arg(column = "meter_role", javaType = String.class),
            @Arg(column = "collect_interval_seconds", javaType = Integer.class),
            @Arg(column = "quality_threshold_pct", javaType = java.math.BigDecimal.class)
    })
    List<DevDevice> findEnabledList(@Param("deviceId") Long deviceId);

    @Select("""
            <script>
            SELECT id, device_sn, device_name, gateway_id, org_id, space_id, device_type_id, protocol_addr, status,
                   settlement_enabled, meter_role, collect_interval_seconds, quality_threshold_pct
            FROM dev_device
            WHERE status = 1 AND id IN
            <foreach collection="deviceIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    @ConstructorArgs({
            @Arg(column = "id", javaType = Long.class),
            @Arg(column = "device_sn", javaType = String.class),
            @Arg(column = "device_name", javaType = String.class),
            @Arg(column = "gateway_id", javaType = Long.class),
            @Arg(column = "org_id", javaType = Long.class),
            @Arg(column = "space_id", javaType = Long.class),
            @Arg(column = "device_type_id", javaType = Long.class),
            @Arg(column = "protocol_addr", javaType = String.class),
            @Arg(column = "status", javaType = Integer.class),
            @Arg(column = "settlement_enabled", javaType = boolean.class),
            @Arg(column = "meter_role", javaType = String.class),
            @Arg(column = "collect_interval_seconds", javaType = Integer.class),
            @Arg(column = "quality_threshold_pct", javaType = java.math.BigDecimal.class)
    })
    List<DevDevice> findEnabledByIds(@Param("deviceIds") List<Long> deviceIds);
}
