package com.parkenergydata.service;

import java.util.Optional;

import com.parkenergydata.cache.RealtimeCacheService;
import com.parkenergydata.dto.RealtimeDeviceSnapshot;
import org.springframework.stereotype.Service;

@Service
public class RealtimeDataService {
    private final RealtimeCacheService cacheService;

    public RealtimeDataService(RealtimeCacheService cacheService) {
        this.cacheService = cacheService;
    }

    public Optional<RealtimeDeviceSnapshot> findRealtime(Long deviceId) {
        return cacheService.findRealtime(deviceId);
    }
}
