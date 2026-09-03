package com.parkenergydata.cache;

import java.time.Duration;
import java.time.Instant;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
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
            Optional<RealtimeDeviceSnapshot> current = findRealtime(snapshot.deviceId());
            if (current.isPresent() && current.get().collectTime().isAfter(snapshot.collectTime())) {
                return;
            }
            redisTemplate.opsForValue().set(deviceKey(snapshot.deviceId()),
                    objectMapper.writeValueAsString(snapshot), properties.realtimeTtl());
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Realtime snapshot serialization failed", ex);
        }
    }

    public Optional<RealtimeDeviceSnapshot> findRealtime(Long deviceId) {
        try {
            String value = redisTemplate.opsForValue().get(deviceKey(deviceId));
            if (value == null) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(value, RealtimeDeviceSnapshot.class));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Realtime snapshot deserialization failed", ex);
        }
    }

    public void rememberAlarmTrigger(Long deviceId, Long ruleId, Duration ttl) {
        redisTemplate.opsForValue().set("alarm:last:" + deviceId + ":" + ruleId, "1", ttl);
    }

    public boolean alarmRecentlyTriggered(Long deviceId, Long ruleId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey("alarm:last:" + deviceId + ":" + ruleId));
    }

    /** Rejects delayed/out-of-order samples and resets continuity after an excessive sample gap. */
    public boolean acceptAlarmSample(Long deviceId, Long ruleId, Instant sampleTime, int maxGapSeconds) {
        String key = "alarm:sample-time:" + deviceId + ":" + ruleId;
        long epoch = sampleTime.toEpochMilli();
        String previous = redisTemplate.opsForValue().get(key);
        if (previous != null) {
            try {
                long previousEpoch = Long.parseLong(previous);
                if (epoch <= previousEpoch) return false;
                if (maxGapSeconds > 0 && epoch - previousEpoch > maxGapSeconds * 1000L) {
                    clearAlarmEvaluationState(deviceId, ruleId);
                }
            } catch (NumberFormatException ignored) {
                clearAlarmEvaluationState(deviceId, ruleId);
            }
        }
        redisTemplate.opsForValue().set(key, String.valueOf(epoch), Duration.ofDays(7));
        return true;
    }

    /** Returns true only after the threshold violation has remained continuous in event time. */
    public boolean alarmViolationDurationReached(Long deviceId, Long ruleId, int durationSeconds, Instant sampleTime) {
        if (durationSeconds <= 0) return true;
        String key = "alarm:violation-start:" + deviceId + ":" + ruleId;
        long now = sampleTime.toEpochMilli();
        String started = redisTemplate.opsForValue().get(key);
        if (started == null) {
            redisTemplate.opsForValue().setIfAbsent(key, String.valueOf(now), Duration.ofSeconds(Math.max(durationSeconds * 4L, 3600L)));
            return false;
        }
        try {
            return now - Long.parseLong(started) >= durationSeconds * 1000L;
        } catch (NumberFormatException invalid) {
            redisTemplate.delete(key);
            return false;
        }
    }

    /** Compatibility overload retained for non-event-time callers. */
    public boolean alarmViolationDurationReached(Long deviceId, Long ruleId, int durationSeconds) {
        return alarmViolationDurationReached(deviceId, ruleId, durationSeconds, Instant.now());
    }

    public boolean alarmRecoverySamplesReached(Long deviceId, Long ruleId, int requiredSamples) {
        if (requiredSamples <= 1) return true;
        String key = "alarm:recovery-count:" + deviceId + ":" + ruleId;
        Long count = redisTemplate.opsForValue().increment(key);
        redisTemplate.expire(key, Duration.ofHours(2));
        return count != null && count >= requiredSamples;
    }

    public void clearAlarmRecovery(Long deviceId, Long ruleId) {
        redisTemplate.delete("alarm:recovery-count:" + deviceId + ":" + ruleId);
    }

    public boolean nOfMTriggered(Long deviceId, Long ruleId, boolean matched, int requiredHits, int windowSamples) {
        String key = "alarm:n-of-m:" + deviceId + ":" + ruleId;
        int size = Math.max(windowSamples, 1);
        redisTemplate.opsForList().rightPush(key, matched ? "1" : "0");
        redisTemplate.opsForList().trim(key, -size, -1);
        redisTemplate.expire(key, Duration.ofHours(2));
        List<String> values = redisTemplate.opsForList().range(key, 0, -1);
        if (values == null || values.size() < size) return false;
        return values.stream().filter("1"::equals).count() >= Math.min(Math.max(requiredHits, 1), size);
    }

    public Optional<BigDecimal> windowAverage(Long deviceId, Long ruleId, Instant sampleTime, BigDecimal value,
                                               int windowSeconds, int windowSamples) {
        String key = "alarm:window:" + deviceId + ":" + ruleId;
        redisTemplate.opsForList().rightPush(key, sampleTime.toEpochMilli() + "|" + value.toPlainString());
        redisTemplate.opsForList().trim(key, -Math.max(windowSamples, 1), -1);
        redisTemplate.expire(key, Duration.ofSeconds(Math.max(windowSeconds * 2L, 3600L)));
        List<String> raw = redisTemplate.opsForList().range(key, 0, -1);
        if (raw == null) return Optional.empty();
        long cutoff = sampleTime.minusSeconds(Math.max(windowSeconds, 1)).toEpochMilli();
        List<BigDecimal> values = new ArrayList<>();
        for (String item : raw) {
            String[] parts = item.split("\\|", 2);
            if (parts.length != 2) continue;
            try {
                if (Long.parseLong(parts[0]) >= cutoff) values.add(new BigDecimal(parts[1]));
            } catch (NumberFormatException ignored) { }
        }
        if (values.size() < Math.min(2, Math.max(windowSamples, 1))) return Optional.empty();
        BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return Optional.of(sum.divide(BigDecimal.valueOf(values.size()), 8, RoundingMode.HALF_UP));
    }

    /** Signed change rate per minute. */
    public Optional<BigDecimal> ratePerMinute(Long deviceId, Long ruleId, Instant sampleTime, BigDecimal value) {
        String key = "alarm:rate:" + deviceId + ":" + ruleId;
        String previous = redisTemplate.opsForValue().get(key);
        redisTemplate.opsForValue().set(key, sampleTime.toEpochMilli() + "|" + value.toPlainString(), Duration.ofDays(1));
        if (previous == null) return Optional.empty();
        String[] parts = previous.split("\\|", 2);
        if (parts.length != 2) return Optional.empty();
        try {
            long elapsedMillis = sampleTime.toEpochMilli() - Long.parseLong(parts[0]);
            if (elapsedMillis <= 0) return Optional.empty();
            return Optional.of(value.subtract(new BigDecimal(parts[1]))
                    .multiply(BigDecimal.valueOf(60_000L))
                    .divide(BigDecimal.valueOf(elapsedMillis), 8, RoundingMode.HALF_UP));
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    public void clearAlarmViolation(Long deviceId, Long ruleId) {
        redisTemplate.delete("alarm:violation-start:" + deviceId + ":" + ruleId);
        redisTemplate.delete("alarm:last:" + deviceId + ":" + ruleId);
    }

    private void clearAlarmEvaluationState(Long deviceId, Long ruleId) {
        redisTemplate.delete(List.of(
                "alarm:violation-start:" + deviceId + ":" + ruleId,
                "alarm:last:" + deviceId + ":" + ruleId,
                "alarm:recovery-count:" + deviceId + ":" + ruleId,
                "alarm:n-of-m:" + deviceId + ":" + ruleId,
                "alarm:window:" + deviceId + ":" + ruleId,
                "alarm:rate:" + deviceId + ":" + ruleId));
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
