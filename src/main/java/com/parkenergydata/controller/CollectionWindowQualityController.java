package com.parkenergydata.controller;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import com.parkenergydata.common.ApiResponse;
import com.parkenergydata.repository.CollectionWindowQualityRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/data/collection-windows")
public class CollectionWindowQualityController {
    private final CollectionWindowQualityRepository repository;

    public CollectionWindowQualityController(CollectionWindowQualityRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(@RequestParam Long deviceId,
                                                        @RequestParam(required = false) String startTime,
                                                        @RequestParam(required = false) String endTime,
                                                        @RequestParam(defaultValue = "288") int limit) {
        Instant end = endTime == null || endTime.isBlank() ? Instant.now() : Instant.parse(endTime);
        Instant start = startTime == null || startTime.isBlank() ? end.minus(24, ChronoUnit.HOURS) : Instant.parse(startTime);
        return ApiResponse.ok(repository.find(deviceId, start, end, limit));
    }
}
