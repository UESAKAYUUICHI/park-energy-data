package com.parkenergydata.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.parkenergydata.service.alarm.AlarmStateRepository;
import com.parkenergydata.service.alarm.AlarmEvaluationState;
import org.springframework.transaction.annotation.Transactional;
import com.parkenergydata.dto.ParsedPoint;
import com.parkenergydata.entity.AlarmRule;
import com.parkenergydata.entity.DevDevice;
import com.parkenergydata.repository.AlarmRuleRepository;
import org.springframework.stereotype.Service;

@Service
public class AlarmEvaluateService {
    private final AlarmRuleRepository repository;
    private final AlarmStateRepository states;

    public AlarmEvaluateService(AlarmRuleRepository repository, AlarmStateRepository states) {
        this.repository = repository;
        this.states = states;
    }

    @Transactional
    public void evaluate(DevDevice device, List<ParsedPoint> points) {
        evaluate(device, points, Instant.now());
    }

    /** Evaluates only fresh, ordered event-time samples. */
    @Transactional
    public void evaluate(DevDevice device, List<ParsedPoint> points, Instant collectTime) {
        Map<String, ParsedPoint> pointMap = points.stream()
                .collect(Collectors.toMap(point -> point.pointCode().toUpperCase(java.util.Locale.ROOT), Function.identity(), (left, right) -> right));
        for (AlarmRule rule : repository.findEnabledForDevice(device.id(), device.orgId())) {
            if(!List.of("THRESHOLD","N_OF_M","WINDOW_AVG","RATE_OF_CHANGE","MISSING_DATA","OFFLINE").contains(normalizedMode(rule)) || isStale(rule,collectTime)) continue;
            String namespace=rule.isProtocolPoint() ? "PROTOCOL" : "RULE";
            AlarmEvaluationState state=states.lock(device.id(),rule.id(),namespace);
            if(!state.accept(collectTime,value(rule.maxSampleGapSeconds(),900),rule.toString())) continue;
            evaluateRule(device,points,pointMap.get(rule.pointCode().toUpperCase(java.util.Locale.ROOT)),rule,collectTime,state);
            states.save(device.id(),rule.id(),namespace,state);
        }
    }

    private void evaluateRule(DevDevice device,List<ParsedPoint> points,ParsedPoint point,
            AlarmRule rule,Instant collectTime,AlarmEvaluationState state) {
        String mode=normalizedMode(rule);
        if("OFFLINE".equals(mode)) {
            recoverIfStable(device,rule,BigDecimal.ZERO,collectTime,state);
            return;
        }
        if("MISSING_DATA".equals(mode)) {
            if(point!=null) recoverIfStable(device,rule,BigDecimal.ZERO,collectTime,state);
            else state.recoveryCount=0;
            return;
        }
        if(point==null || point.numericValue()==null) return;
        Long activeId=repository.findAlarmId(rule,device.id());
        Instant persisted=activeId==null ? null : repository.findAlarmLastSampleTime(activeId);
        if(persisted!=null && !collectTime.isAfter(persisted)) return;
        BigDecimal evaluated=evaluatedValue(rule,point.numericValue(),collectTime,state);
        if(evaluated==null) return;
        boolean triggered=matches(rule,evaluated);
        if("N_OF_M".equals(mode))
            triggered=state.nOfM(triggered,value(rule.requiredHits(),3),value(rule.evaluationWindowSamples(),5));
        if(!triggered) {
            recoverIfStable(device,rule,evaluated,collectTime,state);
            return;
        }
        state.recoveryCount=0;
        if(!state.durationReached(value(rule.durationSeconds(),0),collectTime)
                || state.recentlyTriggered(collectTime)) return;
        String previousStatus=activeId==null ? null : repository.findAlarmStatus(activeId);
        repository.upsertAlarm(rule,device.id(),device.orgId(),evaluated.toPlainString(),thresholdText(rule),collectTime);
        Long alarmId=repository.findAlarmId(rule,device.id());
        if(activeId==null && alarmId!=null)
            repository.insertEventLog(alarmId,"TRIGGER",null,"NEW","测点 "+rule.pointCode()+" 判定值 "+evaluated);
        else if(alarmId!=null && "RECOVERED".equalsIgnoreCase(previousStatus))
            repository.insertEventLog(alarmId,"RETRIGGER","RECOVERED",repository.findAlarmStatus(alarmId),"再次越限，判定值 "+evaluated);
        state.rememberTrigger(collectTime);
    }

    public List<Map<String, Object>> findAlarms(Long deviceId, Integer dealStatus, String startTime, String endTime) {
        return repository.findAlarms(deviceId, dealStatus, startTime, endTime);
    }

    private void recoverIfStable(DevDevice device, AlarmRule rule, BigDecimal value, Instant collectTime,AlarmEvaluationState state) {
        state.clearViolation();
        Long alarmId = repository.findAlarmId(rule, device.id());
        if (alarmId == null || value == null || !recoveryMatches(rule, value)) { state.recoveryCount=0; return; }
        int required = Math.max(value(rule.recoverySamples(), 3), 1);
        if (!state.recovered(required)) return;
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

    private BigDecimal evaluatedValue(AlarmRule rule, BigDecimal rawValue, Instant sampleTime,AlarmEvaluationState state) {
        return switch (normalizedMode(rule)) {
            case "WINDOW_AVG" -> state.average(sampleTime, rawValue,
                    value(rule.windowSeconds(), 300), value(rule.evaluationWindowSamples(), 5)).orElse(null);
            case "RATE_OF_CHANGE" -> state.rate(sampleTime, rawValue).orElse(null);
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
