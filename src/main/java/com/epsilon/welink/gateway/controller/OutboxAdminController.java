package com.epsilon.welink.gateway.controller;

import com.epsilon.welink.common.result.Result;
import com.epsilon.welink.message.service.MessageOutboxService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Outbox 管理端点 (需 X-Internal-Secret).
 */
@Slf4j
@RestController
@RequestMapping("/admin/outbox")
public class OutboxAdminController {

    private final MessageOutboxService messageOutboxService;

    public OutboxAdminController(MessageOutboxService messageOutboxService) {
        this.messageOutboxService = messageOutboxService;
    }

    /**
     * 手动触发 reconcile: 读 Redis 兜底队列, 把双写失败漏写的 outbox_pending 补回 DB.
     * 该接口现在与 OutboxPublisherScheduler 的自动调度做同样的事; 仅在你想立即触发(不等 5 分钟)
     * 时使用. O(漏写数), 正常情况下命中 0 行, 立即返回, 不产生 HikariCP leak 警告.
     */
    @PostMapping("/reconcile")
    public Result<Map<String, Object>> reconcile() {
        int compensated = messageOutboxService.reconcileMissedPending();
        log.info("Manual outbox reconcile completed: compensated={}", compensated);
        return Result.success(Map.of("compensated", compensated));
    }
}
