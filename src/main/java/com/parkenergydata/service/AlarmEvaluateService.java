package com.parkenergydata.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.parkenergydata.cache.RealtimeCacheService;
import com.parkenergydata.dto.ParsedPoint;
import com.parkenergydata.entity.AlarmRule;
import com.parkenergydata.entity.DevDevice;
import com.parkenergydata.repository.AlarmRuleRepository;
import org.springframework.stereotype.Service;

@Service
public class AlarmEvaluateService {
    private final AlarmRuleRepository repository;
    private final RealtimeCacheService cacheService;

    public AlarmEvaluateService(AlarmRuleRepository repository, RealtimeCacheService cacheService) {
        this.repository = repository;
        this.cacheService = cacheService;
    }

    public void evaluate(DevDevice device, List<ParsedPoint> points) {
        evaluate(device, points, Instant.now());
    }

    /** Evaluates only fresh, ordered event-time samples. */
    public void evaluate(DevDevice device, List<ParsedPoint> points, Instant collectTime) {
        Map<String, ParsedPoint> pointMap = points.stream()
                .collect(Collectors.toMap(ParsedPoint::pointCode, Function.identity(), (left, right) -> right));
        for (AlarmRule rule : repository.findEnabledForDevice(device.id(), device.orgId())) {
            ParsedPoint point = pointMap.get(rule.pointCode());
            String mode = normalizedMode(rule);
            if ("OFFLINE".equals(mode)) {
                BigDecimal heartbeatValue = points.stream().map(ParsedPoint::numericValue)
                        .filter(java.util.Objects::nonNull).findFirst().orElse(BigDecimal.ZERO);
                recoverIfStable(device, rule, heartbeatValue, collectTime);
                continue;
            }
            if ("MISSING_DATA".equals(mode) && point != null) {
                recoverIfStable(device, rule, point.numericValue() == null ? BigDecimal.ZERO : point.numericValue(), collectTime);
                continue;
            }
            if (point == null || point.numericValue() == null || isStale(rule, collectTime)) continue;

            Long activeId = repository.findActiveAlarmId(rule.id(), device.id());
            Instant persistedLastSample = activeId == null ? null : repository.findAlarmLastSampleTime(activeId);
            if (persistedLastSample != null && !collectTime.isAfter(persistedLastSample)) continue;
            if (!cacheService.acceptAlarmSample(device.id(), rule.id(), collectTime,
                    Math.max(value(rule.maxSampleGapSeconds(), 900), 1))) continue;

            BigDecimal evaluatedValue = evaluatedValue(device.id(), rule, point.numericValue(), collectTime);
            if (evaluatedValue == null) continue;
            boolean triggered = matches(rule, evaluatedValue);
            if ("N_OF_M".equals(mode)) {
                triggered = cacheService.nOfMTriggered(device.id(), rule.id(), triggered,
                        value(rule.requiredHits(), 3), value(rule.evaluationWindowSamples(), 5));
            }

            if (triggered) {
                cacheService.clearAlarmRecovery(device.id(), rule.id());
                int durationSeconds = Math.max(value(rule.durationSeconds(), 0), 0);
                if (!cacheService.alarmViolationDurationReached(device.id(), rule.id(), durationSeconds, collectTime)) continue;
                if (cacheService.alarmRecentlyTriggered(device.id(), rule.id())) continue;

                String previousStatus = activeId == null ? null : repository.findAlarmStatus(activeId);
                repository.upsertAlarm(rule, device.id(), device.orgId(), evaluatedValue.toPlainString(),
                        thresholdText(rule), collectTime);
                Long alarmId = repository.findActiveAlarmId(rule.id(), device.id());
                if (activeId == null && alarmId != null) {
                    repository.insertEventLog(alarmId, "TRIGGER", null, "NEW",
                            "测点 " + rule.pointCode() + " 触发规则，判定值 " + evaluatedValue.toPlainString());
                } else if (alarmId != null && "RECOVERED".equalsIgnoreCase(previousStatus)) {
                    repository.insertEventLog(alarmId, "RETRIGGER", "RECOVERED", repository.findAlarmStatus(alarmId),
                            "恢复观察期内再次越限，继续原告警，判定值 " + evaluatedValue.toPlainString());
                }
                cacheService.rememberAlarmTrigger(device.id(), rule.id(), Duration.ofSeconds(60));
            } else {
                recoverIfStable(device, rule, evaluatedValue, collectTime);
            }
        }
    }

    public List<Map<String, Object>> findAlarms(Long deviceId, Integer dealStatus, String startTime, String endTime) {
        return repository.findAlarms(deviceId, dealStatus, startTime, endTime);
    }

    private void recoverIfStable(DevDevice device, AlarmRule rule, BigDecimal value, Instant collectTime) {
        cacheService.clearAlarmViolation(device.id(), rule.id());
        Long alarmId = repository.findActiveAlarmId(rule.id(), device.id());
        if (alarmId == null || value == null || !recoveryMatches(rule, value)) return;
        int required = Math.max(value(rule.recoverySamples(), 3), 1);
        if (!cacheService.alarmRecoverySamplesReached(device.id(), rule.id(), required)) return;
        String fromStatus = repository.findAlarmStatus(alarmId);
        if (repository.recoverAlarm(alarmId, value.toPlainString(), collectTime) > 0) {
            repository.insertEventLog(alarmId, "AUTO_RECOVER", fromStatus, "RECOVERED",
                    "测点连续 " + required + " 次满足恢复条件，当前值 " + value.toPlainString());
        }
    }

    private boolean matches(AlarmRule rule, BigDecimal value) {
        String op = rule.compareOperator() == null ? "" : rule.compareOperator().trim().toLowerCase();
        return switch (op) {
            case ">", "gt" -> value.compareTo(rule.thresholdValue()) > 0;
            case ">=", "gte" -> value.compareTo(rule.thresholdValue()) >= 0;
            case "<", "lt" -> value.compareTo(rule.thresholdValue()) < 0;
            case "<=", "lte" -> value.compareTo(rule.thresholdValue()) <= 0;
            case "=", "eq" -> value.compareTo(rule.thresholdValue()) == 0;
            case "between" -> value.compareTo(rule.thresholdMin()) >= 0 && value.compareTo(rule.thresholdMax()) <= 0;
            default -> false;
        };
    }

    private boolean recoveryMatches(AlarmRule rule, BigDecimal value) {
        if ("OFFLINE".equals(normalizedMode(rule)) || "MISSING_DATA".equals(normalizedMode(rule))) return true;
        BigDecimal recovery = rule.recoveryThresholdValue();
        if (recovery == null) return !matches(rule, value);
        String op = rule.compareOperator() == null ? "" : rule.compareOperator().trim().toLowerCase();
        return switch (op) {
            case ">", ">=", "gt", "gte" -> value.compareTo(recovery) <= 0;
            case "<", "<=", "lt", "lte" -> value.compareTo(recovery) >= 0;
            default -> !matches(rule, value);
        };
    }

    private BigDecimal evaluatedValue(Long deviceId, AlarmRule rule, BigDecimal rawValue, Instant sampleTime) {
        return switch (normalizedMode(rule)) {
            case "WINDOW_AVG" -> cacheService.windowAverage(deviceId, rule.id(), sampleTime, rawValue,
                    value(rule.windowSeconds(), 300), value(rule.evaluationWindowSamples(), 5)).orElse(null);
            case "RATE_OF_CHANGE" -> cacheService.ratePerMinute(deviceId, rule.id(), sampleTime, rawValue).orElse(null);
            default -> rawValue;
        };
    }

    private boolean isStale(AlarmRule rule, Instant sampleTime) {
        return sampleTime.isBefore(Instant.now().minusSeconds(Math.max(value(rule.freshnessSeconds(), 900), 1)));
    }

    private String normalizedMode(AlarmRule rule) {
        return rule.evaluationMode() == null ? "THRESHOLD" : rule.evaluationMode().trim().toUpperCase();
    }

    private int value(Integer number, int fallback) {
        return number == null ? fallback : number;
    }

    private String thresholdText(AlarmRule rule) {
        if ("between".equalsIgnoreCase(rule.compareOperator())) return rule.thresholdMin() + "~" + rule.thresholdMax();
        return String.valueOf(rule.thresholdValue());
    }

}
