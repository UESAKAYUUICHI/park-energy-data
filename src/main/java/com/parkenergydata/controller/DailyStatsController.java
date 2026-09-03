package com.parkenergydata.controller;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.parkenergydata.common.ApiResponse;
import com.parkenergydata.service.DailyStatsService;
import com.parkenergydata.service.HourlyStatsService;
import com.parkenergydata.service.TouStatsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/data/statistics")
public class DailyStatsController {
    private final DailyStatsService dailyStatsService;
    private final HourlyStatsService hourlyStatsService;
    private final TouStatsService touStatsService;

    public DailyStatsController(DailyStatsService dailyStatsService, HourlyStatsService hourlyStatsService,
                                TouStatsService touStatsService) {
        this.dailyStatsService = dailyStatsService;
        this.hourlyStatsService = hourlyStatsService;
        this.touStatsService = touStatsService;
    }

    @GetMapping("/hourly")
    public ApiResponse<List<Map<String, Object>>> hourly(@RequestParam(required = false) Long deviceId,
                                                         @RequestParam(required = false) String pointCode,
                                                         @RequestParam(required = false) String startDate,
                                                         @RequestParam(required = false) String endDate) {
        return ApiResponse.ok(hourlyStatsService.findHourly(deviceId, pointCode, startDate, endDate));
    }

    @PostMapping("/hourly/rebuild")
    public ApiResponse<Map<String, Object>> rebuildHourly(@RequestParam String statDate,
                                                           @RequestParam(required = false) Long deviceId) {
        return ApiResponse.ok(hourlyStatsService.rebuildHourly(LocalDate.parse(statDate), deviceId));
    }

    @GetMapping("/daily")
    public ApiResponse<List<Map<String, Object>>> daily(@RequestParam(required = false) Long deviceId,
                                                        @RequestParam(required = false) String pointCode,
                                                        @RequestParam(required = false) String startDate,
                                                        @RequestParam(required = false) String endDate) {
        return ApiResponse.ok(dailyStatsService.findDaily(deviceId, pointCode, startDate, endDate));
    }

    @PostMapping("/daily/rebuild")
    public ApiResponse<Map<String, Object>> rebuildDaily(@RequestParam String statDate,
                                                         @RequestParam(required = false) Long deviceId) {
        return ApiResponse.ok(dailyStatsService.rebuildDaily(LocalDate.parse(statDate), deviceId));
    }

    @GetMapping("/tou")
    public ApiResponse<List<Map<String, Object>>> tou(@RequestParam(required = false) Long deviceId,
                                                       @RequestParam(required = false) String pointCode,
                                                       @RequestParam(required = false) String startDate,
                                                       @RequestParam(required = false) String endDate) {
        return ApiResponse.ok(touStatsService.findTou(deviceId, pointCode, startDate, endDate));
    }

    @PostMapping("/tou/rebuild")
    public ApiResponse<Map<String, Object>> rebuildTou(@RequestParam String statDate,
                                                        @RequestParam(required = false) Long deviceId) {
        return ApiResponse.ok(touStatsService.rebuildTou(LocalDate.parse(statDate), deviceId));
    }
}
