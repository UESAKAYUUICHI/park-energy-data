package com.parkenergydata.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.parkenergydata.entity.DevDevice;
import com.parkenergydata.repository.DeviceRepository;
import org.springframework.stereotype.Service;

/** Short-lived positive cache: hot gateway reports no longer query dev_device per packet. */
@Service
public class IngestDeviceCache {
    private static final Duration TTL = Duration.ofSeconds(60);
    private final DeviceRepository repository;
    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    public IngestDeviceCache(DeviceRepository repository) {
        this.repository = repository;
    }

    public Optional<DevDevice> findEnabled(Long gatewayId, String deviceSn) {
        String key = gatewayId + ":" + deviceSn;
        Entry cached = entries.get(key);
        if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
            return Optional.of(cached.device());
        }
        Optional<DevDevice> loaded = repository.findEnabledByGatewayAndSn(gatewayId, deviceSn);
        loaded.ifPresent(device -> entries.put(key, new Entry(device, Instant.now().plus(TTL))));
        if (entries.size() > 10_000) entries.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(Instant.now()));
        return loaded;
    }

    public void evict(Long gatewayId, String deviceSn) {
        entries.remove(gatewayId + ":" + deviceSn);
    }

    private record Entry(DevDevice device, Instant expiresAt) { }
}
