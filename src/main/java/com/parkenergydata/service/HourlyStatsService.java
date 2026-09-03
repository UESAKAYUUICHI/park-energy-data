package com.parkenergydata.service;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.LocalDate;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.parkenergydata.dto.ParsedPoint;
import com.parkenergydata.entity.DevDevice;
import com.parkenergydata.entity.DevPointDefinition;
import com.parkenergydata.repository.DeviceRepository;
import com.parkenergydata.repository.PointDefinitionRepository;
import com.parkenergydata.repository.CollectionQualityRepository;
import com.parkenergydata.repository.StatsHourlyPointRepository;
import com.parkenergydata.timeseries.TimeSeriesWriter;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HourlyStatsService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private final StatsHourlyPointRepository repository;
    private final DeviceRepository deviceRepository;
    private final PointDefinitionRepository pointDefinitionRepository;
    private final TimeSeriesWriter timeSeriesWriter;
    private final CollectionQualityRepository collectionQualityRepository;
    private final StatsPointBatchWriter batchWriter;

    @Autowired
    public HourlyStatsService(StatsHourlyPointRepository repository, DeviceRepository deviceRepository,
                              PointDefinitionRepository pointDefinitionRepository,
                              TimeSeriesWriter timeSeriesWriter,
                              CollectionQualityRepository collectionQualityRepository,
                              StatsPointBatchWriter batchWriter) {
        this.repository = repository;
        this.deviceRepository = deviceRepository;
        this.pointDefinitionRepository = pointDefinitionRepository;
        this.timeSeriesWriter = timeSeriesWriter;
        this.collectionQualityRepository = collectionQualityRepository;
        this.batchWriter = batchWriter;
    }

    /** Compatibility constructor retained for existing rebuild-only callers and unit tests. */
    public HourlyStatsService(StatsHourlyPointRepository repository, DeviceRepository deviceRepository,
                              PointDefinitionRepository pointDefinitionRepository,
                              TimeSeriesWriter timeSeriesWriter,
                              CollectionQualityRepository collectionQualityRepository) {
        this.repository = repository;
        this.deviceRepository = deviceRepository;
        this.pointDefinitionRepository = pointDefinitionRepository;
        this.timeSeriesWriter = timeSeriesWriter;
        this.collectionQualityRepository = collectionQualityRepository;
        this.batchWriter = null;
    }

    public void updateHourly(DevDevice device, Instant collectTime, List<ParsedPoint> points) {
        ZonedDateTime businessTime = collectTime.atZone(BUSINESS_ZONE);
        int expectedSamples = expectedSamples(device.collectIntervalSeconds());
        if (batchWriter != null) {
            batchWriter.upsertHourly(device, businessTime.toLocalDate(), businessTime.getHour(), Timestamp.from(collectTime),
                    expectedSamples, points);
            return;
        }
        for (ParsedPoint point : points) {
            if (!point.statEnabled() || point.numericValue() == null) continue;
            boolean accumulated = "TOTAL_ACCUMULATED".equalsIgnoreCase(point.businessRole());
            repository.upsert(device.id(), device.deviceTypeId(), device.orgId(), point.pointCode(),
                    businessTime.toLocalDate(), businessTime.getHour(), Timestamp.from(collectTime), point.numericValue(),
                    accumulated ? BigDecimal.ZERO : null, expectedSamples, accumulated ? 1 : 0);
        }
    }

    public List<Map<String, Object>> findHourly(Long deviceId, String pointCode,
                                                 String startDate, String endDate) {
        return repository.findHourly(deviceId, pointCode, startDate, endDate);
    }

    @Transactional
    public Map<String, Object> rebuildHourly(LocalDate statDate, Long deviceId) {
        Instant start = statDate.atStartOfDay(BUSINESS_ZONE).toInstant();
        Instant end = statDate.plusDays(1).atStartOfDay(BUSINESS_ZONE).toInstant().minusMillis(1);
        List<Map<String, Object>> deviceResults = new ArrayList<>();
        int totalRows = 0;
        for (DevDevice device : deviceRepository.findEnabledList(deviceId)) {
            List<HourlyAggregate> aggregates = new ArrayList<>();
            for (DevPointDefinition point : pointDefinitionRepository.findEnabledListByDeviceType(device.deviceTypeId())) {
                if (!point.statEnabled()) {
                    continue;
                }
                List<TimedValue> values = timedValues(timeSeriesWriter.queryHistory(device.id(), point.pointCode(),
                        start.toString(), end.toString(), 100000));
                Map<Integer, List<TimedValue>> byHour = new LinkedHashMap<>();
                for (TimedValue value : values) {
                    ZonedDateTime time = value.time().atZone(BUSINESS_ZONE);
                    if (statDate.equals(time.toLocalDate())) {
                        byHour.computeIfAbsent(time.getHour(), ignored -> new ArrayList<>()).add(value);
                    }
                }
                boolean accumulated = "TOTAL_ACCUMULATED".equalsIgnoreCase(point.businessRole());
                for (Map.Entry<Integer, List<TimedValue>> entry : byHour.entrySet()) {
                    entry.getValue().sort(Comparator.comparing(TimedValue::time));
                    aggregates.add(aggregate(point.pointCode(), entry.getKey(), entry.getValue(), accumulated,
                            expectedSamples(device.collectIntervalSeconds())));
                }
            }

            // Only replace the relational result after every IoTDB query for this device has succeeded.
            repository.deleteDeviceDate(device.id(), statDate);
            for (HourlyAggregate aggregate : aggregates) {
                repository.replaceHourly(device.id(), device.deviceTypeId(), device.orgId(), aggregate.pointCode(),
                        statDate, aggregate.hour(), Timestamp.from(aggregate.firstTime()),
                        Timestamp.from(aggregate.lastTime()), aggregate.start(), aggregate.end(), aggregate.usage(),
                        aggregate.max(), aggregate.min(), aggregate.avg(), aggregate.sampleCount(),
                        aggregate.expectedSamples(), aggregate.completeRate());
            }
            if (aggregates.stream().anyMatch(HourlyAggregate::accumulatedRollback)) {
                collectionQualityRepository.markAccumulatedRollback(device, statDate);
            }
            totalRows += aggregates.size();
            deviceResults.add(Map.of("deviceId", device.id(), "deviceSn", device.deviceSn(),
                    "rowCount", aggregates.size()));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("statDate", statDate);
        result.put("deviceCount", deviceResults.size());
        result.put("rowCount", totalRows);
        result.put("devices", deviceResults);
        return result;
    }

    private HourlyAggregate aggregate(String pointCode, int hour, List<TimedValue> values,
                                      boolean accumulated, int expected) {
        TimedValue first = values.get(0);
        TimedValue last = values.get(values.size() - 1);
        BigDecimal max = values.stream().map(TimedValue::value).max(Comparator.naturalOrder()).orElse(last.value());
        BigDecimal min = values.stream().map(TimedValue::value).min(Comparator.naturalOrder()).orElse(first.value());
        BigDecimal avg = values.stream().map(TimedValue::value).reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(values.size()), 6, RoundingMode.HALF_UP);
        boolean accumulatedRollback = accumulated && last.value().compareTo(first.value()) < 0;
        BigDecimal usage = accumulated ? last.value().subtract(first.value()).max(BigDecimal.ZERO) : null;
        BigDecimal completeRate = BigDecimal.valueOf(values.size()).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(Math.max(expected, 1)), 2, RoundingMode.HALF_UP)
                .min(BigDecimal.valueOf(100));
        return new HourlyAggregate(pointCode, hour, first.time(), last.time(), first.value(), last.value(),
                usage, max, min, avg, values.size(), expected, completeRate, accumulatedRollback);
    }

    private List<TimedValue> timedValues(List<Map<String, Object>> rows) {
        List<TimedValue> values = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Instant time = instantOrNull(row.get("time"));
            BigDecimal value = decimalOrNull(row.get("value"));
            if (value == null) {
                value = row.entrySet().stream()
                        .filter(entry -> !"time".equalsIgnoreCase(entry.getKey())
                                && !"pointCode".equalsIgnoreCase(entry.getKey()))
                        .map(Map.Entry::getValue).map(this::decimalOrNull).filter(Objects::nonNull)
                        .findFirst().orElse(null);
            }
            if (time != null && value != null) {
                values.add(new TimedValue(time, value));
            }
        }
        return values;
    }

    private Instant instantOrNull(Object value) {
        if (value instanceof Number number) return Instant.ofEpochMilli(number.longValue());
        if (value instanceof java.sql.Timestamp timestamp) return timestamp.toInstant();
        if (value instanceof java.util.Date date) return date.toInstant();
        if (value == null) return null;
        try { return Instant.parse(value.toString()); }
        catch (RuntimeException ignored) {
            try { return Instant.ofEpochMilli(Long.parseLong(value.toString())); }
            catch (RuntimeException invalid) { return null; }
        }
    }

    private BigDecimal decimalOrNull(Object value) {
        if (value == null) return null;
        try { return new BigDecimal(value.toString()); }
        catch (NumberFormatException ignored) { return null; }
    }

    static int expectedSamples(Integer collectIntervalSeconds) {
        int interval = collectIntervalSeconds == null || collectIntervalSeconds <= 0
                ? 300 : collectIntervalSeconds;
        return Math.max(1, (3600 + interval - 1) / interval);
    }

    private record TimedValue(Instant time, BigDecimal value) {}
    private record HourlyAggregate(String pointCode, int hour, Instant firstTime, Instant lastTime,
                                   BigDecimal start, BigDecimal end, BigDecimal usage,
                                   BigDecimal max, BigDecimal min, BigDecimal avg,
                                   int sampleCount, int expectedSamples, BigDecimal completeRate,
                                   boolean accumulatedRollback) {}
}
