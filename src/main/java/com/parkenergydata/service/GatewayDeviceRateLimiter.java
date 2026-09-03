package com.parkenergydata.service;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Fixed-window protection. A rejected item is retained as INVALID and can be replayed from the data-quality flow. */
@Service
public class GatewayDeviceRateLimiter {
    private final int permitsPerMinute;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public GatewayDeviceRateLimiter(@Value("${park.ingest.device-rate-limit-per-minute:120}") int permitsPerMinute) {
        this.permitsPerMinute = Math.max(10, permitsPerMinute);
    }

    public boolean tryAcquire(Long gatewayId, String deviceSn) {
        Instant now = Instant.now();
        String key = gatewayId + ":" + deviceSn;
        Window window = windows.compute(key, (ignored, current) -> {
            if (current == null || !current.startedAt().plus(Duration.ofMinutes(1)).isAfter(now)) {
                return new Window(now, new AtomicInteger(1));
            }
            current.count().incrementAndGet();
            return current;
        });
        if (windows.size() > 20_000) windows.entrySet().removeIf(entry ->
                !entry.getValue().startedAt().plus(Duration.ofMinutes(2)).isAfter(now));
        return window.count().get() <= permitsPerMinute;
    }

    public int permitsPerMinute() { return permitsPerMinute; }

    private record Window(Instant startedAt, AtomicInteger count) { }
}
