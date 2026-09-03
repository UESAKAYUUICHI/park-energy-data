package com.parkenergydata.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
    private final ConcurrentHashMap<Long, CacheEntry<DevPointDefinition>> definitions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, CacheEntry<DevPointMapping>> mappings = new ConcurrentHashMap<>();

    public DeviceMetadataService(PointDefinitionRepository definitionRepository, PointMappingRepository mappingRepository) {
        this.definitionRepository = definitionRepository;
        this.mappingRepository = mappingRepository;
    }

    public Map<String, DevPointDefinition> definitions(Long deviceTypeId) {
        return cached(definitions, deviceTypeId, () -> definitionRepository.findEnabledListByDeviceType(deviceTypeId).stream()
                .collect(Collectors.toMap(DevPointDefinition::pointCode, Function.identity())));
    }

    public Map<String, DevPointMapping> mappings(Long deviceTypeId) {
        return cached(mappings, deviceTypeId, () -> mappingRepository.findMappingListByDeviceType(deviceTypeId).stream()
                .collect(Collectors.toMap(DevPointMapping::pointCode, Function.identity())));
    }

    public void evict(Long deviceTypeId) { definitions.remove(deviceTypeId); mappings.remove(deviceTypeId); }

    private <T> Map<String, T> cached(ConcurrentHashMap<Long, CacheEntry<T>> cache, Long key,
                                      java.util.function.Supplier<Map<String, T>> loader) {
        CacheEntry<T> existing = cache.get(key);
        if (existing != null && existing.expiresAt().isAfter(Instant.now())) return existing.values();
        Map<String, T> loaded = Map.copyOf(loader.get());
        cache.put(key, new CacheEntry<>(loaded, Instant.now().plus(Duration.ofMinutes(5))));
        return loaded;
    }

    private record CacheEntry<T>(Map<String, T> values, Instant expiresAt) { }
}
