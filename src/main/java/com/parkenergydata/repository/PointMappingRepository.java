package com.parkenergydata.repository;

import java.math.BigDecimal;
import java.util.List;

import com.parkenergydata.entity.DevPointMapping;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface PointMappingRepository {
    @Select("""
            SELECT id, device_type_id, point_code, protocol_type, source_path,
                   function_code, register_address, register_length, value_type, byte_order,
                   scale_factor, offset_value, expression, required
            FROM dev_point_mapping
            WHERE device_type_id = #{deviceTypeId}
            """)
    @ConstructorArgs({
            @Arg(column = "id", javaType = Long.class),
            @Arg(column = "device_type_id", javaType = Long.class),
            @Arg(column = "point_code", javaType = String.class),
            @Arg(column = "protocol_type", javaType = String.class),
            @Arg(column = "source_path", javaType = String.class),
            @Arg(column = "function_code", javaType = String.class),
            @Arg(column = "register_address", javaType = Integer.class),
            @Arg(column = "register_length", javaType = Integer.class),
            @Arg(column = "value_type", javaType = String.class),
            @Arg(column = "byte_order", javaType = String.class),
            @Arg(column = "scale_factor", javaType = BigDecimal.class),
            @Arg(column = "offset_value", javaType = BigDecimal.class),
            @Arg(column = "expression", javaType = String.class),
            @Arg(column = "required", javaType = boolean.class)
    })
    List<DevPointMapping> findMappingListByDeviceType(@Param("deviceTypeId") Long deviceTypeId);
}
