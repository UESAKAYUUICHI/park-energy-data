package com.parkenergydata.service;

import java.util.Optional;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.Duration;
import java.time.Instant;

import com.parkenergydata.cache.RealtimeCacheService;
import com.parkenergydata.dto.RealtimeDeviceSnapshot;
import com.parkenergydata.entity.DevDevice;
import com.parkenergydata.repository.DeviceRepository;
import org.springframework.stereotype.Service;

@Service
public class RealtimeDataService {
    private final RealtimeCacheService cacheService;
    private final DeviceRepository deviceRepository;

    public RealtimeDataService(RealtimeCacheService cacheService, DeviceRepository deviceRepository) {
        this.cacheService = cacheService;
        this.deviceRepository = deviceRepository;
    }

    public Optional<RealtimeDeviceSnapshot> findRealtime(Long deviceId) {
        return cacheService.findRealtime(deviceId).map(snapshot -> refreshFreshness(deviceId, snapshot));
    }

    public Map<String, RealtimeDeviceSnapshot> findRealtimeBatch(List<Long> deviceIds) {
        if (deviceIds == null || deviceIds.isEmpty()) return Map.of();
        Map<Long, Long> intervals = new LinkedHashMap<>();
        for (DevDevice device : deviceRepository.findEnabledByIds(deviceIds)) {
            intervals.put(device.id(), device.collectIntervalSeconds() == null ? 300L
                    : device.collectIntervalSeconds().longValue());
        }
        Map<String, RealtimeDeviceSnapshot> result = new LinkedHashMap<>();
        for (Long deviceId : deviceIds) {
            cacheService.findRealtime(deviceId).ifPresent(snapshot -> result.put(String.valueOf(deviceId),
                    refreshFreshness(snapshot, intervals.getOrDefault(deviceId, 300L))));
        }
        return result;
    }

    private RealtimeDeviceSnapshot refreshFreshness(Long deviceId, RealtimeDeviceSnapshot snapshot) {
        long interval = deviceRepository.findEnabledList(deviceId).stream().findFirst()
                .map(device -> device.collectIntervalSeconds() == null ? 300L
                        : device.collectIntervalSeconds().longValue())
                .orElse(300L);
        return refreshFreshness(snapshot, interval);
    }

    private RealtimeDeviceSnapshot refreshFreshness(RealtimeDeviceSnapshot snapshot, long interval) {
        long ageSeconds = snapshot.collectTime() == null ? Long.MAX_VALUE
                : Math.max(0L, Duration.between(snapshot.collectTime(), Instant.now()).getSeconds());
        String freshness = ageSeconds > Math.max(30L, interval * 3L) ? "STALE" : "FRESH";
        return new RealtimeDeviceSnapshot(snapshot.deviceId(), snapshot.deviceSn(), snapshot.gatewayId(),
                snapshot.orgId(), snapshot.collectTime(), snapshot.receiveTime(), snapshot.delaySeconds(),
                freshness, snapshot.qualityStatus(), snapshot.points(), snapshot.pointDetails(), snapshot.dataQuality());
    }
}
