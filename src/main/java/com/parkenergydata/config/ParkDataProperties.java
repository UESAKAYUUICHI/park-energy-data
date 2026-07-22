package com.parkenergydata.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "park.data")
public record ParkDataProperties(
        boolean consumerEnabled,
        int messageDedupTtlMinutes,
        int realtimeTtlMinutes,
        Iotdb iotdb
) {
    public Duration messageDedupTtl() {
        return Duration.ofMinutes(messageDedupTtlMinutes <= 0 ? 60 : messageDedupTtlMinutes);
    }

    public Duration realtimeTtl() {
        return Duration.ofMinutes(realtimeTtlMinutes <= 0 ? 30 : realtimeTtlMinutes);
    }

    public record Iotdb(boolean enabled, String driverClassName, String jdbcUrl, String username, String password,
                        String storageGroup) {
    }
}
