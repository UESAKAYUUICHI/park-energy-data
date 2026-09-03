package com.parkenergydata.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.parkenergydata.dto.ParsedPoint;
import com.parkenergydata.entity.DevDevice;
import com.parkenergydata.entity.DevPointDefinition;
import com.parkenergydata.repository.DeviceRepository;
import com.parkenergydata.repository.MeterReadingStateRepository;
import com.parkenergydata.repository.PointDefinitionRepository;
import com.parkenergydata.repository.StatsTouDailyRepository;
import com.parkenergydata.repository.TariffPlanRepository;
import com.parkenergydata.repository.TariffPlanRepository.TariffPeriod;
import com.parkenergydata.repository.TariffPlanRepository.TariffPlan;
import com.parkenergydata.timeseries.TimeSeriesWriter;
import org.springframework.stereotype.Service;

/** Builds auditable TOU usage buckets from the true interval between accumulated meter readings. */
@Service
public class TouStatsService {
    private final StatsTouDailyRepository statsRepository;
    private final TariffPlanRepository tariffRepository;
    private final DeviceRepository deviceRepository;
    private final PointDefinitionRepository pointRepository;
    private final TimeSeriesWriter timeSeriesWriter;
    private final MeterReadingStateRepository readingStateRepository;

    public TouStatsService(StatsTouDailyRepository statsRepository, TariffPlanRepository tariffRepository,
                           DeviceRepository deviceRepository, PointDefinitionRepository pointRepository,
                           TimeSeriesWriter timeSeriesWriter, MeterReadingStateRepository readingStateRepository) {
        this.statsRepository = statsRepository;
        this.tariffRepository = tariffRepository;
        this.deviceRepository = deviceRepository;
        this.pointRepository = pointRepository;
        this.timeSeriesWriter = timeSeriesWriter;
        this.readingStateRepository = readingStateRepository;
    }

    public void updateTou(DevDevice device, Instant collectedAt, List<ParsedPoint> points,
                          Map<String, DevPointDefinition> definitions) {
        if (!device.settlementEnabled()) return;
        for (ParsedPoint point : points) {
            DevPointDefinition definition = definitions.get(point.pointCode());
            if (definition == null || !definition.billable() || point.numericValue() == null
                    || !"TOTAL_ACCUMULATED".equalsIgnoreCase(point.businessRole())) continue;
            Reading current = new Reading(collectedAt, point.numericValue());
            MeterReadingStateRepository.Reading saved = readingStateRepository.lock(device.id(), point.pointCode());
            if (saved == null) {
                readingStateRepository.save(device.id(), point.pointCode(), collectedAt, point.numericValue());
                continue;
            }
            Reading previous = new Reading(saved.collectTime(), saved.value());
            if (!current.time().isAfter(previous.time())) {
                continue;
            }
            if (current.value().compareTo(previous.value()) < 0) {
                readingStateRepository.save(device.id(), point.pointCode(), collectedAt, point.numericValue());
                continue;
            }
            allocate(device, point.pointCode(), previous, current);
            readingStateRepository.save(device.id(), point.pointCode(), collectedAt, point.numericValue());
        }
    }

    public List<Map<String, Object>> findTou(Long deviceId, String pointCode, String startDate, String endDate) {
        return statsRepository.find(deviceId, pointCode, startDate, endDate);
    }

    public Map<String, Object> rebuildTou(LocalDate statDate, Long onlyDeviceId) {
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        Instant start = statDate.atStartOfDay(zone).toInstant();
        Instant end = statDate.plusDays(1).atStartOfDay(zone).toInstant();
        List<Map<String, Object>> rebuilt = new ArrayList<>();
        for (DevDevice device : deviceRepository.findEnabledList(onlyDeviceId)) {
            if (!device.settlementEnabled()) continue;
            statsRepository.deleteDaily(device.id(), statDate);
            for (DevPointDefinition point : pointRepository.findEnabledListByDeviceType(device.deviceTypeId())) {
                if (!point.billable() || !"TOTAL_ACCUMULATED".equalsIgnoreCase(point.businessRole())) continue;
                List<Reading> readings = readings(timeSeriesWriter.queryHistory(device.id(), point.pointCode(),
                        start.minusSeconds(24 * 3600L).toString(), end.toString(), 100000));
                readings.sort(Comparator.comparing(Reading::time));
                int intervals = 0;
                for (int i = 1; i < readings.size(); i++) {
                    Reading before = readings.get(i - 1);
                    Reading after = readings.get(i);
                    if (after.time().isAfter(start) && !after.time().isAfter(end)) {
                        allocate(device, point.pointCode(), before, after);
                        intervals++;
                    }
                }
                if (intervals > 0) rebuilt.add(Map.of("deviceId", device.id(), "pointCode", point.pointCode(), "intervals", intervals));
            }
        }
        return Map.of("statDate", statDate, "rebuilt", rebuilt, "count", rebuilt.size());
    }

    private void allocate(DevDevice device, String pointCode, Reading before, Reading after) {
        if (!after.time().isAfter(before.time()) || after.value().compareTo(before.value()) < 0) return;
        BigDecimal delta = after.value().subtract(before.value());
        if (delta.compareTo(BigDecimal.ZERO) == 0) return;
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        LocalDate startDate = before.time().atZone(zone).toLocalDate();
        LocalDate endDate = after.time().atZone(zone).toLocalDate();
        for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
            for (TariffPlan plan : tariffRepository.findApplicablePlans(device.orgId(), date)) {
                allocatePlan(device, pointCode, before, after, delta, plan, date, zone);
            }
        }
    }

    private void allocatePlan(DevDevice device, String pointCode, Reading before, Reading after, BigDecimal delta,
                              TariffPlan plan, LocalDate date, ZoneId zone) {
        Instant dayStart = date.atStartOfDay(zone).toInstant();
        Instant dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant();
        Instant intervalStart = before.time().isAfter(dayStart) ? before.time() : dayStart;
        Instant intervalEnd = after.time().isBefore(dayEnd) ? after.time() : dayEnd;
        if (!intervalEnd.isAfter(intervalStart)) return;
        List<TariffPeriod> periods = tariffRepository.periods(plan.id(), date);
        List<Instant> boundaries = boundaries(periods, date, zone, intervalStart, intervalEnd);
        long fullMillis = after.time().toEpochMilli() - before.time().toEpochMilli();
        for (int i = 1; i < boundaries.size(); i++) {
            Instant partStart = boundaries.get(i - 1);
            Instant partEnd = boundaries.get(i);
            TariffPeriod period = periodAt(periods, partStart.plusMillis((partEnd.toEpochMilli() - partStart.toEpochMilli()) / 2), zone);
            if (period == null) continue;
            BigDecimal proportion = BigDecimal.valueOf(partEnd.toEpochMilli() - partStart.toEpochMilli())
                    .divide(BigDecimal.valueOf(fullMillis), 12, RoundingMode.HALF_UP);
            BigDecimal usage = delta.multiply(proportion);
            if (usage.compareTo(BigDecimal.ZERO) > 0) {
                statsRepository.add(device.id(), device.deviceTypeId(), device.orgId(), pointCode, date,
                        plan.id(), plan.version(), period.periodCode(), usage);
            }
        }
    }

    private List<Instant> boundaries(List<TariffPeriod> periods, LocalDate date, ZoneId zone,
                                     Instant start, Instant end) {
        List<Instant> result = new ArrayList<>();
        result.add(start);
        result.add(end);
        for (TariffPeriod period : periods) {
            Instant periodStart = LocalDateTime.of(date, period.startTime()).atZone(zone).toInstant();
            Instant periodEnd = LocalDateTime.of(date, period.endTime()).atZone(zone).toInstant();
            if (!periodEnd.isAfter(periodStart)) periodEnd = periodEnd.plusSeconds(24 * 3600);
            if (periodStart.isAfter(start) && periodStart.isBefore(end)) result.add(periodStart);
            if (periodEnd.isAfter(start) && periodEnd.isBefore(end)) result.add(periodEnd);
        }
        return result.stream().distinct().sorted().toList();
    }

    private TariffPeriod periodAt(List<TariffPeriod> periods, Instant instant, ZoneId zone) {
        LocalTime time = instant.atZone(zone).toLocalTime();
        int second = time.toSecondOfDay();
        return periods.stream().filter(period -> {
            int start = period.startTime().toSecondOfDay();
            int end = period.endTime().toSecondOfDay();
            return end > start ? second >= start && second < end : second >= start || second < end;
        }).findFirst().orElse(null);
    }

    private List<Reading> readings(List<Map<String, Object>> rows) {
        List<Reading> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Instant time = null;
            BigDecimal value = null;
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                if ("time".equalsIgnoreCase(entry.getKey())) time = instant(entry.getValue());
                else if (value == null) value = decimal(entry.getValue());
            }
            if (time != null && value != null) result.add(new Reading(time, value));
        }
        return result;
    }

    private Instant instant(Object value) {
        if (value instanceof Timestamp timestamp) return timestamp.toInstant();
        if (value instanceof java.util.Date date) return date.toInstant();
        if (value instanceof Number number) return Instant.ofEpochMilli(number.longValue());
        try { return Instant.parse(String.valueOf(value)); } catch (Exception ignored) { return null; }
    }

    private BigDecimal decimal(Object value) {
        if (value == null) return null;
        try { return new BigDecimal(Objects.toString(value)); } catch (NumberFormatException ignored) { return null; }
    }

    private record Reading(Instant time, BigDecimal value) { }
}
