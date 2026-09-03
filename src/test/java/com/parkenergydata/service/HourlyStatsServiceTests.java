package com.parkenergydata.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.parkenergydata.dto.ParsedPoint;
import com.parkenergydata.entity.DevDevice;
import com.parkenergydata.entity.DevPointDefinition;
import com.parkenergydata.repository.StatsHourlyPointRepository;
import com.parkenergydata.repository.DeviceRepository;
import com.parkenergydata.repository.PointDefinitionRepository;
import com.parkenergydata.repository.CollectionQualityRepository;
import com.parkenergydata.timeseries.TimeSeriesWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HourlyStatsServiceTests {
    @Mock private StatsHourlyPointRepository repository;
    @Mock private DeviceRepository deviceRepository;
    @Mock private PointDefinitionRepository pointDefinitionRepository;
    @Mock private TimeSeriesWriter timeSeriesWriter;
    @Mock private CollectionQualityRepository collectionQualityRepository;

    @Test
    void calculatesExpectedSamplesFromDeviceCollectionInterval() {
        assertThat(HourlyStatsService.expectedSamples(300)).isEqualTo(12);
        assertThat(HourlyStatsService.expectedSamples(7)).isEqualTo(515);
        assertThat(HourlyStatsService.expectedSamples(null)).isEqualTo(12);
    }

    @Test
    void aggregatesAllTwelveMeterPointsAndMarksOnlyEnergyAsAccumulated() {
        HourlyStatsService service = service();
        DevDevice device = new DevDevice(7L, "325-METER-001", "325电表", 3L, 9L, 6L,
                null, 1, false, "GENERAL", 300, BigDecimal.valueOf(95));
        Instant collectTime = Instant.parse("2026-08-11T02:15:00Z");
        List<String> codes = List.of("VOLTAGE_A", "VOLTAGE_B", "VOLTAGE_C", "CURRENT_A",
                "CURRENT_B", "CURRENT_C", "POWER_FACTOR_TOTAL", "FREQUENCY",
                "ACTIVE_POWER_TOTAL", "REACTIVE_POWER_TOTAL", "APPARENT_POWER_TOTAL",
                "FORWARD_ACTIVE_ENERGY");
        List<ParsedPoint> points = codes.stream()
                .map(code -> new ParsedPoint(code, "DOUBLE",
                        "FORWARD_ACTIVE_ENERGY".equals(code) ? "TOTAL_ACCUMULATED" : "INSTANT_VALUE",
                        true, BigDecimal.TEN, BigDecimal.TEN))
                .toList();

        service.updateHourly(device, collectTime, points);

        for (String code : codes) {
            int accumulated = "FORWARD_ACTIVE_ENERGY".equals(code) ? 1 : 0;
            verify(repository).upsert(eq(7L), eq(6L), eq(9L), eq(code),
                    eq(java.time.LocalDate.of(2026, 8, 11)), eq(10), eq(java.sql.Timestamp.from(collectTime)),
                    eq(BigDecimal.TEN), accumulated == 1 ? eq(BigDecimal.ZERO) : org.mockito.ArgumentMatchers.isNull(),
                    eq(12), eq(accumulated));
        }
    }

    @Test
    void rebuildsHourlyEnergyFromIotDbHistoryBeforeReplacingRelationalRows() {
        HourlyStatsService service = service();
        DevDevice device = new DevDevice(7L, "WZBC-LS2-M-325", "325电表", 3L, 9L, 6L,
                null, 1, true, "SETTLEMENT", 300, BigDecimal.valueOf(95));
        DevPointDefinition energy = new DevPointDefinition(12L, 6L, "FORWARD_ACTIVE_ENERGY",
                "正向有功总电能", "DOUBLE", "kWh", 2, "TOTAL_ACCUMULATED", true, true, true);
        Instant first = Instant.parse("2026-08-11T02:05:00Z");
        Instant last = Instant.parse("2026-08-11T02:55:00Z");
        when(deviceRepository.findEnabledList(7L)).thenReturn(List.of(device));
        when(pointDefinitionRepository.findEnabledListByDeviceType(6L)).thenReturn(List.of(energy));
        when(timeSeriesWriter.queryHistory(eq(7L), eq("FORWARD_ACTIVE_ENERGY"),
                eq("2026-08-10T16:00:00Z"), eq("2026-08-11T15:59:59.999Z"), eq(100000)))
                .thenReturn(List.of(
                        Map.of("time", first.toEpochMilli(), "value", new BigDecimal("12843.27")),
                        Map.of("time", last.toEpochMilli(), "value", new BigDecimal("12844.02"))));

        Map<String, Object> result = service.rebuildHourly(java.time.LocalDate.of(2026, 8, 11), 7L);

        verify(repository).deleteDeviceDate(7L, java.time.LocalDate.of(2026, 8, 11));
        verify(repository).replaceHourly(eq(7L), eq(6L), eq(9L), eq("FORWARD_ACTIVE_ENERGY"),
                eq(java.time.LocalDate.of(2026, 8, 11)), eq(10), eq(java.sql.Timestamp.from(first)),
                eq(java.sql.Timestamp.from(last)), eq(new BigDecimal("12843.27")),
                eq(new BigDecimal("12844.02")), eq(new BigDecimal("0.75")),
                eq(new BigDecimal("12844.02")), eq(new BigDecimal("12843.27")),
                eq(new BigDecimal("12843.645000")), eq(2), eq(12), eq(new BigDecimal("16.67")));
        assertThat(result).containsEntry("deviceCount", 1).containsEntry("rowCount", 1);
    }

    @Test
    void marksCollectionDayAbnormalWhenAccumulatedReadingRollsBack() {
        HourlyStatsService service = service();
        DevDevice device = new DevDevice(7L, "WZBC-LS2-M-325", "325电表", 3L, 9L, 6L,
                null, 1, true, "SETTLEMENT", 300, BigDecimal.valueOf(95));
        DevPointDefinition energy = new DevPointDefinition(12L, 6L, "FORWARD_ACTIVE_ENERGY",
                "正向有功总电能", "DOUBLE", "kWh", 2, "TOTAL_ACCUMULATED", true, true, true);
        Instant first = Instant.parse("2026-08-11T02:05:00Z");
        Instant last = Instant.parse("2026-08-11T02:55:00Z");
        when(deviceRepository.findEnabledList(7L)).thenReturn(List.of(device));
        when(pointDefinitionRepository.findEnabledListByDeviceType(6L)).thenReturn(List.of(energy));
        when(timeSeriesWriter.queryHistory(eq(7L), eq("FORWARD_ACTIVE_ENERGY"),
                eq("2026-08-10T16:00:00Z"), eq("2026-08-11T15:59:59.999Z"), eq(100000)))
                .thenReturn(List.of(
                        Map.of("time", first.toEpochMilli(), "value", new BigDecimal("12844.02")),
                        Map.of("time", last.toEpochMilli(), "value", new BigDecimal("12843.27"))));

        service.rebuildHourly(java.time.LocalDate.of(2026, 8, 11), 7L);

        verify(collectionQualityRepository).markAccumulatedRollback(device,
                java.time.LocalDate.of(2026, 8, 11));
        verify(repository).replaceHourly(eq(7L), eq(6L), eq(9L), eq("FORWARD_ACTIVE_ENERGY"),
                eq(java.time.LocalDate.of(2026, 8, 11)), eq(10), eq(java.sql.Timestamp.from(first)),
                eq(java.sql.Timestamp.from(last)), eq(new BigDecimal("12844.02")),
                eq(new BigDecimal("12843.27")), eq(BigDecimal.ZERO),
                eq(new BigDecimal("12844.02")), eq(new BigDecimal("12843.27")),
                eq(new BigDecimal("12843.645000")), eq(2), eq(12), eq(new BigDecimal("16.67")));
    }

    private HourlyStatsService service() {
        return new HourlyStatsService(repository, deviceRepository, pointDefinitionRepository,
                timeSeriesWriter, collectionQualityRepository);
    }
}
