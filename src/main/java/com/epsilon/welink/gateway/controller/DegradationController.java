package com.epsilon.welink.gateway.controller;

import com.epsilon.welink.common.DegradationManager;
import com.epsilon.welink.common.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/admin/degradation")
public class DegradationController {

    private final DegradationManager degradationManager;

    public DegradationController(DegradationManager degradationManager) {
        this.degradationManager = degradationManager;
    }

    @GetMapping
    public Result<Map<String, Object>> status() {
        return Result.success(Map.of(
                "level", degradationManager.getCurrentLevel(),
                "description", degradationManager.getLevelDescription(),
                "readReceiptEnabled", degradationManager.isReadReceiptEnabled(),
                "presenceBroadcastEnabled", degradationManager.isPresenceBroadcastEnabled(),
                "largeGroupPushEnabled", degradationManager.isLargeGroupPushEnabled(),
                "fileUploadEnabled", degradationManager.isFileUploadEnabled(),
                "textMessageOnly", degradationManager.isTextMessageOnly()
        ));
    }

    @PostMapping("/level/{level}")
    public Result<Map<String, Object>> setLevel(@PathVariable int level) {
        degradationManager.setLevel(level);
        return status();
    }
}
