package com.epsilon.welink.message.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Outbox 定时器.
 * <p>
 * 两个自动任务:
 * <ul>
 *   <li><b>publishDueOutboxEvents</b>: 高频(默认 3s)扫 outbox_pending 单表索引, 推 Kafka.</li>
 *   <li><b>reconcile</b>: 低频(默认 5 分钟)把 Redis 兜底队列里 createOutboxRecord 双写
 *       失败的 outboxId 补到 outbox_pending. O(漏写数), 正常情况下 0 行命中, 无 broadcast 无 leak 噪音.</li>
 * </ul>
 * <p>
 * <b>已知噪音</b>: 启动后 30 秒会出现 1 条针对 HikariPool-11 (unsharded ds) 的 leak detection
 * 警告. 这是 ShardingSphere 首次访问 unsharded ds 时做 metadata lazy load 持有连接 >30s 引发的,
 * 启动后稳态运行不会再触发. 不影响功能.
 */
@Slf4j
@Component
public class OutboxPublisherScheduler {

    private final MessageOutboxService messageOutboxService;

    @Value("${welink.outbox.batch-size:100}")
    private int batchSize;

    public OutboxPublisherScheduler(MessageOutboxService messageOutboxService) {
        this.messageOutboxService = messageOutboxService;
    }

    @Scheduled(fixedDelayString = "${welink.outbox.publish-interval-ms:3000}")
    public void publishDueOutboxEvents() {
        messageOutboxService.publishDueEvents(batchSize);
    }

    /**
     * 兜底: 把 Redis 兜底队列里漏写的 outbox_pending 补回 DB. O(漏写数), 无 broadcast.
     * 正常情况下 Redis 队列为空, 这个任务退化为一个 Redis SMEMBERS 调用.
     */
    @Scheduled(fixedDelayString = "${welink.outbox.reconcile-interval-ms:300000}", initialDelay = 60000)
    public void reconcile() {
        try {
            messageOutboxService.reconcileMissedPending();
        } catch (Exception e) {
            log.warn("Outbox reconcile failed", e);
        }
    }
}
