package com.parkenergydata.service.alarm;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class AlarmEvaluationState {
    public String ruleVersion;
    public Long lastSample;
    public Long violationStart;
    public Long triggerUntil;
    public int recoveryCount;
    public List<Boolean> matches=new ArrayList<>();
    public List<Measurement> measurements=new ArrayList<>();
    public Measurement previous;
    public record Measurement(long time,BigDecimal value) {}

    public boolean accept(Instant time,int gapSeconds,String version) {
        long epoch=time.toEpochMilli();
        if(lastSample!=null && epoch<=lastSample) return false;
        if(!java.util.Objects.equals(ruleVersion,version)
                || lastSample!=null && epoch-lastSample>Math.max(gapSeconds,1)*1000L) reset();
        ruleVersion=version;
        lastSample=epoch;
        return true;
    }
    public void reset() {
        violationStart=null; triggerUntil=null; recoveryCount=0;
        matches.clear(); measurements.clear(); previous=null;
    }
    public boolean durationReached(int seconds,Instant time) {
        if(violationStart==null) violationStart=time.toEpochMilli();
        return time.toEpochMilli()-violationStart>=Math.max(seconds,0)*1000L;
    }
    public boolean recentlyTriggered(Instant time) {
        return triggerUntil!=null && time.toEpochMilli()<triggerUntil;
    }
    public void rememberTrigger(Instant time) { triggerUntil=time.plusSeconds(60).toEpochMilli(); }
    public void clearViolation() { violationStart=null; triggerUntil=null; }
    public boolean recovered(int required) { return ++recoveryCount>=Math.max(required,1); }
    public boolean nOfM(boolean match,int required,int size) {
        size=Math.min(Math.max(size,1),10000);
        matches.add(match);
        while(matches.size()>size) matches.remove(0);
        return matches.size()==size && matches.stream().filter(Boolean::booleanValue).count()
                >=Math.min(Math.max(required,1),size);
    }
    public Optional<BigDecimal> average(Instant time,BigDecimal value,int seconds,int samples) {
        int limit=Math.min(Math.max(samples,1),10000);
        measurements.add(new Measurement(time.toEpochMilli(),value));
        long cutoff=time.minusSeconds(Math.max(seconds,1)).toEpochMilli();
        measurements.removeIf(item->item.time()<cutoff);
        while(measurements.size()>limit) measurements.remove(0);
        if(measurements.size()<Math.min(2,limit)) return Optional.empty();
        BigDecimal sum=measurements.stream().map(Measurement::value).reduce(BigDecimal.ZERO,BigDecimal::add);
        return Optional.of(sum.divide(BigDecimal.valueOf(measurements.size()),8,RoundingMode.HALF_UP));
    }
    public Optional<BigDecimal> rate(Instant time,BigDecimal value) {
        Measurement old=previous;
        previous=new Measurement(time.toEpochMilli(),value);
        if(old==null || previous.time()<=old.time()) return Optional.empty();
        return Optional.of(value.subtract(old.value()).multiply(BigDecimal.valueOf(60000))
                .divide(BigDecimal.valueOf(previous.time()-old.time()),8,RoundingMode.HALF_UP));
    }
}
