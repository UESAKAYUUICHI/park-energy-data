package com.parkenergydata.service;

import java.math.BigDecimal;
import java.time.Duration;
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
        Map<String, ParsedPoint> pointMap = points.stream().collect(Collectors.toMap(ParsedPoint::pointCode, Function.identity()));
        for (AlarmRule rule : repository.findEnabledForDevice(device.id(), device.orgId())) {
            ParsedPoint point = pointMap.get(rule.pointCode());
            if (point == null || point.numericValue() == null) {
                continue;
            }
            if (matches(rule, point.numericValue()) && !cacheService.alarmRecentlyTriggered(device.id(), rule.id())) {
                repository.insertAlarm(rule, device.id(), device.orgId(), point.numericValue().toPlainString(), thresholdText(rule));
                int suppressSeconds = Math.max(rule.durationSeconds() == null ? 0 : rule.durationSeconds(), 60);
                cacheService.rememberAlarmTrigger(device.id(), rule.id(), Duration.ofSeconds(suppressSeconds));
            }
        }
    }

    public List<Map<String, Object>> findAlarms(Long deviceId, Integer dealStatus, String startTime, String endTime) {
        return repository.findAlarms(deviceId, dealStatus, startTime, endTime);
    }

    private boolean matches(AlarmRule rule, BigDecimal value) {
        String op = rule.compareOperator() == null ? "" : rule.compareOperator().trim().toLowerCase();
        return switch (op) {
            case ">" -> value.compareTo(rule.thresholdValue()) > 0;
            case ">=" -> value.compareTo(rule.thresholdValue()) >= 0;
            case "<" -> value.compareTo(rule.thresholdValue()) < 0;
            case "<=" -> value.compareTo(rule.thresholdValue()) <= 0;
            case "=" -> value.compareTo(rule.thresholdValue()) == 0;
            case "between" -> value.compareTo(rule.thresholdMin()) >= 0 && value.compareTo(rule.thresholdMax()) <= 0;
            default -> false;
        };
    }

    private String thresholdText(AlarmRule rule) {
        if ("between".equalsIgnoreCase(rule.compareOperator())) {
            return rule.thresholdMin() + "~" + rule.thresholdMax();
        }
        return String.valueOf(rule.thresholdValue());
    }
}
