package com.parkenergydata.service;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.parkenergydata.entity.DevPointDefinition;
import com.parkenergydata.entity.DevPointMapping;
import com.parkenergydata.repository.PointDefinitionRepository;
import com.parkenergydata.repository.PointMappingRepository;
import org.springframework.stereotype.Service;

@Service
public class DeviceMetadataService {
    private final PointDefinitionRepository definitionRepository;
    private final PointMappingRepository mappingRepository;

    public DeviceMetadataService(PointDefinitionRepository definitionRepository, PointMappingRepository mappingRepository) {
        this.definitionRepository = definitionRepository;
        this.mappingRepository = mappingRepository;
    }

    public Map<String, DevPointDefinition> definitions(Long deviceTypeId) {
        return definitionRepository.findEnabledListByDeviceType(deviceTypeId).stream()
                .collect(Collectors.toMap(DevPointDefinition::pointCode, Function.identity()));
    }

    public Map<String, DevPointMapping> mappings(Long deviceTypeId) {
        return mappingRepository.findJsonMappingListByDeviceType(deviceTypeId).stream()
                .collect(Collectors.toMap(DevPointMapping::pointCode, Function.identity()));
    }
}
