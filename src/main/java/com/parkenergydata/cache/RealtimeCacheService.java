package com.parkenergydata.cache;

import java.time.Duration;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkenergydata.config.ParkDataProperties;
import com.parkenergydata.dto.RealtimeDeviceSnapshot;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class RealtimeCacheService {
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final ParkDataProperties properties;

    public RealtimeCacheService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper, ParkDataProperties properties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public boolean markProcessed(String messageId) {
        if (messageId == null || messageId.isBlank()) {
            return false;
        }
        redisTemplate.opsForValue().set(processedKey(messageId), "1", properties.messageDedupTtl());
        redisTemplate.delete(processingKey(messageId));
        return true;
    }

    public boolean isProcessed(String messageId) {
        return messageId != null && !messageId.isBlank()
                && Boolean.TRUE.equals(redisTemplate.hasKey(processedKey(messageId)));
    }

    public boolean tryStartProcessing(String messageId) {
        if (messageId == null || messageId.isBlank()) {
            return true;
        }
        if (isProcessed(messageId)) {
            return false;
        }
        Boolean first = redisTemplate.opsForValue()
                .setIfAbsent(processingKey(messageId), "1", Duration.ofMinutes(5));
        return Boolean.TRUE.equals(first);
    }

    public void clearProcessing(String messageId) {
        if (messageId != null && !messageId.isBlank()) {
            redisTemplate.delete(processingKey(messageId));
        }
    }

    public void saveRealtime(RealtimeDeviceSnapshot snapshot) {
        try {
            redisTemplate.opsForValue().set(deviceKey(snapshot.deviceId()),
                    objectMapper.writeValueAsString(snapshot), properties.realtimeTtl());
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Realtime snapshot serialization failed", ex);
        }
    }

    public Optional<RealtimeDeviceSnapshot> findRealtime(Long deviceId) {
        String value = redisTemplate.opsForValue().get(deviceKey(deviceId));
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(value, RealtimeDeviceSnapshot.class));
        } catch (JsonProcessingException ex) {
            return Optional.empty();
        }
    }

    public void rememberAlarmTrigger(Long deviceId, Long ruleId, Duration ttl) {
        redisTemplate.opsForValue().set("alarm:last:" + deviceId + ":" + ruleId, "1", ttl);
    }

    public boolean alarmRecentlyTriggered(Long deviceId, Long ruleId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey("alarm:last:" + deviceId + ":" + ruleId));
    }

    private String deviceKey(Long deviceId) {
        return "realtime:device:" + deviceId;
    }

    private String processedKey(String messageId) {
        return "data:consumer:message:done:" + messageId;
    }

    private String processingKey(String messageId) {
        return "data:consumer:message:processing:" + messageId;
    }
}
