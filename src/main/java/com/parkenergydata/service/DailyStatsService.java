package com.parkenergydata.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.parkenergydata.dto.ParsedPoint;
import com.parkenergydata.entity.DevDevice;
import com.parkenergydata.entity.DevPointDefinition;
import com.parkenergydata.repository.DeviceRepository;
import com.parkenergydata.repository.PointDefinitionRepository;
import com.parkenergydata.repository.StatsDailyPointRepository;
import com.parkenergydata.timeseries.TimeSeriesWriter;
import org.springframework.stereotype.Service;

@Service
public class DailyStatsService {
    private final StatsDailyPointRepository repository;
    private final DeviceRepository deviceRepository;
    private final PointDefinitionRepository pointDefinitionRepository;
    private final TimeSeriesWriter timeSeriesWriter;

    public DailyStatsService(StatsDailyPointRepository repository, DeviceRepository deviceRepository,
                             PointDefinitionRepository pointDefinitionRepository, TimeSeriesWriter timeSeriesWriter) {
        this.repository = repository;
        this.deviceRepository = deviceRepository;
        this.pointDefinitionRepository = pointDefinitionRepository;
        this.timeSeriesWriter = timeSeriesWriter;
    }

    public void updateDaily(DevDevice device, Instant collectTime, List<ParsedPoint> points) {
        LocalDate statDate = collectTime.atZone(ZoneId.systemDefault()).toLocalDate();
        for (ParsedPoint point : points) {
            if (point.statEnabled() && point.numericValue() != null) {
                boolean accumulated = "TOTAL_ACCUMULATED".equalsIgnoreCase(point.businessRole());
                repository.upsert(device.id(), device.deviceTypeId(), device.orgId(), point.pointCode(), statDate,
                        point.numericValue(), accumulated ? java.math.BigDecimal.ZERO : null, accumulated ? 1 : 0);
            }
        }
    }

    public List<Map<String, Object>> findDaily(Long deviceId, String pointCode, String startDate, String endDate) {
        return repository.findDaily(deviceId, pointCode, startDate, endDate);
    }

    public Map<String, Object> rebuildDaily(LocalDate statDate, Long deviceId) {
        List<Map<String, Object>> rebuilt = new ArrayList<>();
        ZoneId zone = ZoneId.systemDefault();
        String startTime = statDate.atStartOfDay(zone).toInstant().toString();
        String endTime = statDate.plusDays(1).atStartOfDay(zone).toInstant().minusMillis(1).toString();
        for (DevDevice device : deviceRepository.findEnabledList(deviceId)) {
            for (DevPointDefinition point : pointDefinitionRepository.findEnabledListByDeviceType(device.deviceTypeId())) {
                if (!point.statEnabled()) {
                    continue;
                }
                List<BigDecimal> values = numericValues(timeSeriesWriter.queryHistory(device.id(), point.pointCode(), startTime, endTime, 100000));
                if (values.isEmpty()) {
                    continue;
                }
                BigDecimal start = values.get(0);
                BigDecimal end = values.get(values.size() - 1);
                BigDecimal max = values.stream().max(Comparator.naturalOrder()).orElse(end);
                BigDecimal min = values.stream().min(Comparator.naturalOrder()).orElse(start);
                BigDecimal avg = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(BigDecimal.valueOf(values.size()), 6, RoundingMode.HALF_UP);
                BigDecimal usage = "TOTAL_ACCUMULATED".equalsIgnoreCase(point.businessRole()) ? end.subtract(start) : null;
                repository.replaceDaily(device.id(), device.deviceTypeId(), device.orgId(), point.pointCode(), statDate,
                        start, end, usage, max, min, avg, BigDecimal.valueOf(100));
                rebuilt.add(Map.of("deviceId", device.id(), "pointCode", point.pointCode(), "count", values.size()));
            }
        }
        return Map.of("statDate", statDate, "rebuilt", rebuilt, "count", rebuilt.size());
    }

    private List<BigDecimal> numericValues(List<Map<String, Object>> rows) {
        List<BigDecimal> values = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                if ("Time".equalsIgnoreCase(entry.getKey()) || "time".equalsIgnoreCase(entry.getKey())) {
                    continue;
                }
                BigDecimal value = decimalOrNull(entry.getValue());
                if (value != null) {
                    values.add(value);
                    break;
                }
            }
        }
        return values;
    }

    private BigDecimal decimalOrNull(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(Objects.toString(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
