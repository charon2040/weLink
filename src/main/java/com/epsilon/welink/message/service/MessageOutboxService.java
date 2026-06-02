package com.epsilon.welink.message.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.epsilon.welink.message.constant.MessageOutboxConstants;
import com.epsilon.welink.message.entity.Message;
import com.epsilon.welink.message.entity.MessageOutbox;
import com.epsilon.welink.message.entity.OutboxPending;
import com.epsilon.welink.message.mapper.MessageOutboxMapper;
import com.epsilon.welink.message.mapper.OutboxPendingMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.shardingsphere.infra.hint.HintManager;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Outbox 发布服务.
 * <p>
 * 轮询走非分片的 outbox_pending 索引表 (welink.outbox_pending), 命中后用 outbox_shard +
 * target_user_id 精确路由到分片的 message_outbox 拿账本数据 & 发送 Kafka. 发送成功删 pending 行,
 * 失败更新双方 retry 状态.
 * <p>
 * 与旧版 (扫 8 ds × 64 表) 相比: 每轮 1 条 SQL vs 512 条; HikariCP leak 误报消失.
 */
@Slf4j
@Service
public class MessageOutboxService {

    private static final int OUTBOX_SHARD_COUNT = 8;
    private static final int OUTBOX_TABLE_COUNT = 64;

    private final MessageOutboxMapper messageOutboxMapper;
    private final OutboxPendingMapper outboxPendingMapper;
    private final MessageService messageService;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final RedisTemplate<String, Object> redisTemplate;

    @Value("${welink.outbox.max-retries:6}")
    private int maxRetries;

    @Value("${welink.outbox.base-retry-delay-seconds:5}")
    private int baseRetryDelaySeconds;

    public MessageOutboxService(MessageOutboxMapper messageOutboxMapper,
                                OutboxPendingMapper outboxPendingMapper,
                                MessageService messageService,
                                KafkaTemplate<String, Object> kafkaTemplate,
                                RedisTemplate<String, Object> redisTemplate) {
        this.messageOutboxMapper = messageOutboxMapper;
        this.outboxPendingMapper = outboxPendingMapper;
        this.messageService = messageService;
        this.kafkaTemplate = kafkaTemplate;
        this.redisTemplate = redisTemplate;
    }

    // 注: 第一次访问 unsharded 数据源时, ShardingSphere 内部会做 metadata lazy load,
    // 持有 Hikari 连接 > 30s, 触发 1 条 leak detection 警告. 这是 ShardingSphere 行为,
    // 启动后稳态运行不会再触发. 见 OutboxPublisherScheduler javadoc.

    /**
     * 单实例 / 多实例都安全: 用 Redis 锁占用本轮 batch. 不再按 shard 分片锁(没必要,
     * outbox_pending 是单表, 选行已经天然唯一).
     */
    public void publishDueEvents(int limit) {
        String lockKey = "outbox:poll:lock:pending";
        String lockValue = UUID.randomUUID().toString();
        Boolean locked = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, lockValue, Duration.ofSeconds(10));
        if (Boolean.FALSE.equals(locked)) {
            return;
        }

        String traceId = "outbox:" + UUID.randomUUID().toString().substring(0, 8);
        MDC.put("traceId", traceId);
        try {
            LocalDateTime now = LocalDateTime.now();
            List<OutboxPending> due = outboxPendingMapper.selectList(new LambdaQueryWrapper<OutboxPending>()
                    .in(OutboxPending::getStatus, 0, 1)               // PENDING / FAILED
                    .le(OutboxPending::getNextRetryAt, now)
                    .orderByAsc(OutboxPending::getNextRetryAt)
                    .last("limit " + limit));
            if (due.isEmpty()) return;
            log.debug("publishDueEvents picked {} rows", due.size());
            publishBatch(due);
        } finally {
            MDC.remove("traceId");
            redisTemplate.execute(
                    new org.springframework.data.redis.core.script.DefaultRedisScript<>(
                            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
                            Long.class),
                    java.util.Collections.singletonList(lockKey), lockValue);
        }
    }

    private void publishBatch(List<OutboxPending> due) {
        // 1) 按 outbox_shard 分组, 每组用 HintManager 精确路由批量拉 message_outbox
        Map<Integer, List<OutboxPending>> byShard = new HashMap<>();
        for (OutboxPending p : due) {
            byShard.computeIfAbsent(p.getOutboxShard(), k -> new java.util.ArrayList<>()).add(p);
        }
        Map<Long, MessageOutbox> outboxById = new HashMap<>();
        for (Map.Entry<Integer, List<OutboxPending>> entry : byShard.entrySet()) {
            int shardIdx = entry.getKey();
            List<Long> ids = entry.getValue().stream().map(OutboxPending::getOutboxId).toList();
            try (HintManager hm = HintManager.getInstance()) {
                hm.addDatabaseShardingValue("message_outbox", shardIdx);
                List<MessageOutbox> rows = messageOutboxMapper.selectBatchIds(ids);
                for (MessageOutbox row : rows) {
                    outboxById.put(row.getId(), row);
                }
            }
        }

        // 2) 加载消息, 准备 PENDING -> PUBLISHING, 然后异步发送
        for (OutboxPending p : due) {
            MessageOutbox outbox = outboxById.get(p.getOutboxId());
            if (outbox == null) {
                // message_outbox 行不存在: 数据残留, 直接清 pending
                log.warn("outbox row missing for pending id={} outboxId={}, removing pending", p.getId(), p.getOutboxId());
                outboxPendingMapper.deleteById(p.getId());
                continue;
            }
            // 已发布: 清 pending
            if (outbox.getStatus() != null && outbox.getStatus().equals(MessageOutboxConstants.STATUS_PUBLISHED)) {
                outboxPendingMapper.deleteById(p.getId());
                continue;
            }
            // 超过最大重试: 标 FAILED, 删 pending(不再轮询), 留 message_outbox 作审计
            if (p.getRetryCount() != null && p.getRetryCount() >= maxRetries) {
                outboxPendingMapper.deleteById(p.getId());
                continue;
            }

            Message message = messageService.getMessageByMsgIdRouted(
                    outbox.getMsgId(), outbox.getConversationId(), outbox.getMessageCreatedAt());
            if (message == null) {
                bumpRetry(p, outbox, "message not found");
                continue;
            }

            // PENDING -> PUBLISHING (message_outbox 路由更新)
            updateMessageOutboxStatus(outbox, MessageOutboxConstants.STATUS_PUBLISHING, null);

            final Long pendingId = p.getId();
            final Long outboxId = p.getOutboxId();
            final Long targetUserId = p.getTargetUserId();
            final int outboxShard = p.getOutboxShard();
            String kafkaKey = String.valueOf(targetUserId);
            kafkaTemplate.send(p.getTopic(), kafkaKey, message)
                    .whenComplete((result, ex) -> {
                        if (ex == null) {
                            onPublishSuccess(pendingId, outboxId, outboxShard, targetUserId);
                        } else {
                            onPublishFailure(pendingId, outboxId, outboxShard, targetUserId, ex.getMessage());
                        }
                    });
        }
    }

    private void onPublishSuccess(Long pendingId, Long outboxId, int outboxShard, Long targetUserId) {
        try {
            outboxPendingMapper.deleteById(pendingId);
        } catch (Exception e) {
            log.warn("Failed to delete outbox_pending id={}", pendingId, e);
        }
        try (HintManager hm = HintManager.getInstance()) {
            hm.addDatabaseShardingValue("message_outbox", outboxShard);
            MessageOutbox latest = messageOutboxMapper.selectById(outboxId);
            if (latest != null) {
                latest.setStatus(MessageOutboxConstants.STATUS_PUBLISHED);
                latest.setLastError(null);
                messageOutboxMapper.updateById(latest);
            }
        } catch (Exception e) {
            log.warn("Failed to mark message_outbox PUBLISHED, outboxId={}", outboxId, e);
        }
    }

    private void onPublishFailure(Long pendingId, Long outboxId, int outboxShard, Long targetUserId, String error) {
        try {
            OutboxPending p = outboxPendingMapper.selectById(pendingId);
            if (p == null) return;
            int currentRetry = p.getRetryCount() == null ? 0 : p.getRetryCount();
            int nextRetry = currentRetry + 1;
            long delaySeconds = (long) baseRetryDelaySeconds * (1L << Math.min(nextRetry, 6));
            p.setRetryCount(nextRetry);
            p.setStatus(1);
            p.setNextRetryAt(LocalDateTime.now().plusSeconds(delaySeconds));
            outboxPendingMapper.updateById(p);
            log.warn("Outbox publish failed: pendingId={}, outboxId={}, retryCount={}, error={}",
                    pendingId, outboxId, nextRetry, truncate(error));
        } catch (Exception e) {
            log.error("Failed to bump retry on outbox_pending id={}", pendingId, e);
        }
        try (HintManager hm = HintManager.getInstance()) {
            hm.addDatabaseShardingValue("message_outbox", outboxShard);
            MessageOutbox latest = messageOutboxMapper.selectById(outboxId);
            if (latest != null) {
                int retry = (latest.getRetryCount() == null ? 0 : latest.getRetryCount()) + 1;
                latest.setRetryCount(retry);
                latest.setStatus(MessageOutboxConstants.STATUS_FAILED);
                latest.setLastError(truncate(error));
                long delaySeconds = (long) baseRetryDelaySeconds * (1L << Math.min(retry, 6));
                latest.setNextRetryAt(LocalDateTime.now().plusSeconds(delaySeconds));
                messageOutboxMapper.updateById(latest);
            }
        } catch (Exception e) {
            log.warn("Failed to bump retry on message_outbox id={}", outboxId, e);
        }
    }

    private void bumpRetry(OutboxPending p, MessageOutbox outbox, String error) {
        onPublishFailure(p.getId(), outbox.getId(), p.getOutboxShard(), p.getTargetUserId(), error);
    }

    private void updateMessageOutboxStatus(MessageOutbox outbox, Integer status, String error) {
        try (HintManager hm = HintManager.getInstance()) {
            hm.addDatabaseShardingValue("message_outbox", (int) (outbox.getTargetUserId() % OUTBOX_SHARD_COUNT));
            outbox.setStatus(status);
            if (error != null) {
                outbox.setLastError(truncate(error));
            }
            messageOutboxMapper.updateById(outbox);
        } catch (Exception e) {
            log.warn("Failed to update message_outbox status id={}", outbox.getId(), e);
        }
    }

    private String truncate(String s) {
        if (s == null) return null;
        return s.length() > 500 ? s.substring(0, 500) : s;
    }

    /**
     * 兜底 reconciliation: 把 createOutboxRecord 双写时 Redis 兜底记下的漏写补到 outbox_pending.
     * <p>
     * 正常路径: outbox_pending insert 成功 -> Redis 兜底为空 -> 本任务 0 行命中.
     * 异常路径: DB 抖动导致 outbox_pending insert 失败 -> MessageService 把 outboxId 与
     * 重建 pending 行需要的字段 (targetUserId/topic/msgId) 一并写入 Redis -> 本任务读 Redis
     * 集合, 直接 INSERT outbox_pending, 不再 broadcast 扫 message_outbox.
     * <p>
     * 因此 reconcile 是 O(漏写数) 而不是 O(512 表), 没有 HikariCP leak 噪音, 可自动调度.
     */
    public int reconcileMissedPending() {
        java.util.Set<Object> ids;
        try {
            ids = redisTemplate.opsForSet().members(
                    com.epsilon.welink.common.constant.RedisConstants.OUTBOX_RECONCILE_SET);
        } catch (Exception e) {
            log.warn("Failed to read outbox reconcile set from Redis", e);
            return 0;
        }
        if (ids == null || ids.isEmpty()) return 0;

        int compensated = 0;
        for (Object idObj : ids) {
            Long outboxId;
            try {
                outboxId = Long.valueOf(idObj.toString());
            } catch (NumberFormatException e) {
                redisTemplate.opsForSet().remove(
                        com.epsilon.welink.common.constant.RedisConstants.OUTBOX_RECONCILE_SET, idObj);
                continue;
            }
            String hashKey = com.epsilon.welink.common.constant.RedisConstants.OUTBOX_RECONCILE_PREFIX + outboxId;
            java.util.Map<Object, Object> hash = redisTemplate.opsForHash().entries(hashKey);
            if (hash == null || hash.isEmpty()) {
                // Hash 已过期或被清, 同步清 set
                redisTemplate.opsForSet().remove(
                        com.epsilon.welink.common.constant.RedisConstants.OUTBOX_RECONCILE_SET, idObj);
                continue;
            }
            try {
                Long targetUserId = Long.valueOf(hash.get("targetUserId").toString());
                String topic = hash.get("topic").toString();
                String msgId = hash.get("msgId").toString();
                OutboxPending p = new OutboxPending();
                p.setOutboxId(outboxId);
                p.setOutboxShard((int) (targetUserId % OUTBOX_SHARD_COUNT));
                p.setTargetUserId(targetUserId);
                p.setTopic(topic);
                p.setMsgId(msgId);
                p.setStatus(0);
                p.setRetryCount(0);
                p.setNextRetryAt(LocalDateTime.now());
                try {
                    outboxPendingMapper.insert(p);
                    compensated++;
                } catch (org.springframework.dao.DuplicateKeyException ignored) {
                    // 其他实例已补
                }
            } catch (Exception e) {
                log.warn("Failed to compensate outbox_pending from Redis: outboxId={}", outboxId, e);
                continue;
            }
            // 不管成败 (除上面 catch continue 外) 都清 Redis, 不再重试
            redisTemplate.delete(hashKey);
            redisTemplate.opsForSet().remove(
                    com.epsilon.welink.common.constant.RedisConstants.OUTBOX_RECONCILE_SET, idObj);
        }
        if (compensated > 0) {
            log.info("Outbox reconcile via Redis: compensated {} missing pending rows", compensated);
        }
        return compensated;
    }
}
