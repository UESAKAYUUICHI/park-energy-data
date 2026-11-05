package com.parkenergydata.service.alarm;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AlarmEvaluationStateTest {
    final Instant t=Instant.parse("2026-11-05T00:00:00Z");
    @Test void rejectsDuplicateAndOutOfOrderSamples() {
        var state=new AlarmEvaluationState();
        assertTrue(state.accept(t,300,"v1"));
        assertFalse(state.accept(t,300,"v1"));
        assertFalse(state.accept(t.minusSeconds(1),300,"v1"));
    }
    @Test void gapClearsViolationAndRecoveryProgress() {
        var state=new AlarmEvaluationState();
        state.accept(t,60,"v1"); state.durationReached(120,t); state.recovered(3);
        state.accept(t.plusSeconds(61),60,"v1");
        assertNull(state.violationStart); assertEquals(0,state.recoveryCount);
    }
    @Test void revisionClearsPriorRuleWindow() {
        var state=new AlarmEvaluationState();
        state.accept(t,300,"v1"); state.nOfM(true,2,3);
        state.accept(t.plusSeconds(1),300,"v2");
        assertTrue(state.matches.isEmpty());
    }
    @Test void durationUsesEventTime() {
        var state=new AlarmEvaluationState();
        assertFalse(state.durationReached(60,t));
        assertFalse(state.durationReached(60,t.plusSeconds(59)));
        assertTrue(state.durationReached(60,t.plusSeconds(60)));
    }
    @Test void nOfMRequiresFullWindowAndExpiresOldHits() {
        var state=new AlarmEvaluationState();
        assertFalse(state.nOfM(true,2,3));
        assertFalse(state.nOfM(true,2,3));
        assertTrue(state.nOfM(false,2,3));
        assertFalse(state.nOfM(false,2,3));
    }
    @Test void staleValuesDoNotEnterAverage() {
        var state=new AlarmEvaluationState();
        assertTrue(state.average(t,new BigDecimal("100"),60,5).isEmpty());
        assertTrue(state.average(t.plusSeconds(61),new BigDecimal("20"),60,5).isEmpty());
        assertEquals(0,new BigDecimal("25").compareTo(state.average(t.plusSeconds(62),new BigDecimal("30"),60,5).orElseThrow()));
    }
    @Test void signedRateUsesActualElapsedTime() {
        var state=new AlarmEvaluationState();
        assertTrue(state.rate(t,new BigDecimal("10")).isEmpty());
        assertEquals(0,new BigDecimal("-2").compareTo(state.rate(t.plusSeconds(30),new BigDecimal("9")).orElseThrow()));
    }
}
