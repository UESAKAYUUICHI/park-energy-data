package com.parkenergydata.controller;

import java.util.List;
import java.util.Map;

import com.parkenergydata.common.ApiResponse;
import com.parkenergydata.service.AlarmEvaluateService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/data/alarms")
public class AlarmController {
    private final AlarmEvaluateService alarmEvaluateService;

    public AlarmController(AlarmEvaluateService alarmEvaluateService) {
        this.alarmEvaluateService = alarmEvaluateService;
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> alarms(@RequestParam(required = false) Long deviceId,
                                                        @RequestParam(required = false) Integer dealStatus,
                                                        @RequestParam(required = false) String startTime,
                                                        @RequestParam(required = false) String endTime) {
        return ApiResponse.ok(alarmEvaluateService.findAlarms(deviceId, dealStatus, startTime, endTime));
    }
}
