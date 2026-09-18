package com.parkenergydata.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.parkenergydata.entity.DevPointDefinition;
import com.parkenergydata.repository.PointDefinitionRepository;
import org.springframework.stereotype.Service;

@Service
public class DeviceMetadataService {
    private final PointDefinitionRepository definitionRepository;
    private final ConcurrentHashMap<Long, CacheEntry<DevPointDefinition>> definitions = new ConcurrentHashMap<>();

    public DeviceMetadataService(PointDefinitionRepository definitionRepository) {
        this.definitionRepository = definitionRepository;
    }

    public Map<String, DevPointDefinition> definitions(Long deviceTypeId) {
        return cached(definitions, deviceTypeId, () -> definitionRepository.findEnabledListByDeviceType(deviceTypeId).stream()
                .collect(Collectors.toMap(DevPointDefinition::pointCode, Function.identity())));
    }

    public void evict(Long deviceTypeId) {
        definitions.remove(deviceTypeId);
    }

    private <K, T> Map<String, T> cached(ConcurrentHashMap<K, CacheEntry<T>> cache, K key,
                                      java.util.function.Supplier<Map<String, T>> loader) {
        CacheEntry<T> existing = cache.get(key);
        if (existing != null && existing.expiresAt().isAfter(Instant.now())) return existing.values();
        Map<String, T> loaded = Map.copyOf(loader.get());
        cache.put(key, new CacheEntry<>(loaded, Instant.now().plus(Duration.ofMinutes(5))));
        return loaded;
    }

    private record CacheEntry<T>(Map<String, T> values, Instant expiresAt) { }
}
