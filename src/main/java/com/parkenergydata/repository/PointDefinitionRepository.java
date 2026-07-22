package com.parkenergydata.repository;

import java.util.List;

import com.parkenergydata.entity.DevPointDefinition;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface PointDefinitionRepository {
    @Select("""
            SELECT id, device_type_id, point_code, point_name, data_type, unit, precision_scale,
                   business_role, billable, stat_enabled, enabled
            FROM dev_point_definition
            WHERE device_type_id = #{deviceTypeId} AND enabled = 1
            ORDER BY sort ASC, id ASC
            """)
    @ConstructorArgs({
            @Arg(column = "id", javaType = Long.class),
            @Arg(column = "device_type_id", javaType = Long.class),
            @Arg(column = "point_code", javaType = String.class),
            @Arg(column = "point_name", javaType = String.class),
            @Arg(column = "data_type", javaType = String.class),
            @Arg(column = "unit", javaType = String.class),
            @Arg(column = "precision_scale", javaType = Integer.class),
            @Arg(column = "business_role", javaType = String.class),
            @Arg(column = "billable", javaType = boolean.class),
            @Arg(column = "stat_enabled", javaType = boolean.class),
            @Arg(column = "enabled", javaType = boolean.class)
    })
    List<DevPointDefinition> findEnabledListByDeviceType(@Param("deviceTypeId") Long deviceTypeId);
}
