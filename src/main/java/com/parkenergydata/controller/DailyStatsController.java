package com.parkenergydata.controller;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.parkenergydata.common.ApiResponse;
import com.parkenergydata.service.DailyStatsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/data/statistics")
public class DailyStatsController {
    private final DailyStatsService dailyStatsService;

    public DailyStatsController(DailyStatsService dailyStatsService) {
        this.dailyStatsService = dailyStatsService;
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
}
