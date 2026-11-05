package com.parkenergydata.service.ingest;

import com.parkenergydata.service.DailyStatsService;
import com.parkenergydata.service.HourlyStatsService;
import com.parkenergydata.service.TouStatsService;
import com.parkenergydata.repository.CollectionQualityRepository;
import com.parkenergydata.repository.CollectionWindowQualityRepository;
import org.springframework.stereotype.Service;

@Service
public class EnergyStatisticsUpdater {
    private final DailyStatsService daily;
    private final HourlyStatsService hourly;
    private final TouStatsService tou;
    private final CollectionQualityRepository quality;
    private final CollectionWindowQualityRepository windows;

    public EnergyStatisticsUpdater(DailyStatsService daily, HourlyStatsService hourly, TouStatsService tou,
                                   CollectionQualityRepository quality, CollectionWindowQualityRepository windows) {
        this.daily = daily;
        this.hourly = hourly;
        this.tou = tou;
        this.quality = quality;
        this.windows = windows;
    }

    public void update(PreparedTelemetry sample) {
        daily.updateDaily(sample.device(), sample.collectedAt(), sample.points());
        hourly.updateHourly(sample.device(), sample.collectedAt(), sample.points());
        quality.recordAcceptedMeter(sample.device(), sample.collectedAt());
        windows.record(sample.device(), sample.collectedAt(), sample.sampleIntervalSeconds(), sample.reportWindowSeconds());
        tou.updateTou(sample.device(), sample.collectedAt(), sample.points(), sample.definitions());
    }
}
