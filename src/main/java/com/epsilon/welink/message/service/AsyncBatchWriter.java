package com.epsilon.welink.message.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.epsilon.welink.common.constant.RedisConstants;
import com.epsilon.welink.message.constant.MessageInboxConstants;
import com.epsilon.welink.message.constant.MessageOutboxConstants;
import com.epsilon.welink.message.entity.MessageInbox;
import com.epsilon.welink.message.entity.MessageOutbox;
import com.epsilon.welink.message.entity.OutboxPending;
import com.epsilon.welink.message.mapper.MessageInboxMapper;
import com.epsilon.welink.message.mapper.MessageOutboxMapper;
import com.epsilon.welink.message.mapper.OutboxPendingMapper;
import jakarta.annotation.PreDestroy;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class AsyncBatchWriter {

    private final LinkedBlockingQueue<Object> queue = new LinkedBlockingQueue<>(10000);

    private final MessageInboxMapper messageInboxMapper;
    private final MessageOutboxMapper messageOutboxMapper;
    private final OutboxPendingMapper outboxPendingMapper;
    private final RedisTemplate<String, Object> redisTemplate;

    public AsyncBatchWriter(MessageInboxMapper messageInboxMapper,
                            MessageOutboxMapper messageOutboxMapper,
                            OutboxPendingMapper outboxPendingMapper,
                            RedisTemplate<String, Object> redisTemplate) {
        this.messageInboxMapper = messageInboxMapper;
        this.messageOutboxMapper = messageOutboxMapper;
        this.outboxPendingMapper = outboxPendingMapper;
        this.redisTemplate = redisTemplate;
    }

    @Data
    @AllArgsConstructor
    public static class InboxTask {
        String msgId;
        Long receiverId;
        Integer conversationType;
        Long conversationId;
    }

    @Data
    @AllArgsConstructor
    public static class OutboxTask {
        String msgId;
        Long targetUserId;
        String topic;
        Long conversationId;
        LocalDateTime messageCreatedAt;
    }

    public void submitInboxTask(InboxTask task) {
        if (!queue.offer(task)) {
            syncWriteInbox(task);
        }
    }

    public void submitInboxTasks(List<InboxTask> tasks) {
        for (InboxTask t : tasks) {
            if (!queue.offer(t)) {
                syncWriteInbox(t);
            }
        }
    }

    public void submitOutboxTask(OutboxTask task) {
        if (!queue.offer(task)) {
            syncWriteOutbox(task);
        }
    }

    public void submitOutboxTasks(List<OutboxTask> tasks) {
        for (OutboxTask t : tasks) {
            if (!queue.offer(t)) {
                syncWriteOutbox(t);
            }
        }
    }

    @Scheduled(fixedDelay = 100)
    public void flush() {
        List<Object> drain = new ArrayList<>(200);
        int count = queue.drainTo(drain, 200);
        if (count == 0) return;

        List<InboxTask> inboxes = new ArrayList<>();
        List<OutboxTask> outboxes = new ArrayList<>();
        for (Object obj : drain) {
            if (obj instanceof InboxTask) inboxes.add((InboxTask) obj);
            else if (obj instanceof OutboxTask) outboxes.add((OutboxTask) obj);
        }

        if (!inboxes.isEmpty()) {
            flushInboxBatch(inboxes);
        }
        if (!outboxes.isEmpty()) {
            flushOutboxBatch(outboxes);
        }
    }

    @PreDestroy
    public void shutdown() {
        int remaining = queue.size();
        if (remaining > 0) {
            log.info("AsyncBatchWriter draining {} remaining tasks", remaining);
            flush();
        }
    }

    private void flushInboxBatch(List<InboxTask> tasks) {
        Map<Integer, List<MessageInbox>> groups = new LinkedHashMap<>();
        for (InboxTask t : tasks) {
            MessageInbox inbox = new MessageInbox();
            inbox.setMsgId(t.msgId);
            inbox.setReceiverId(t.receiverId);
            inbox.setConversationType(t.conversationType);
            inbox.setConversationId(t.conversationId);
            inbox.setStatus(MessageInboxConstants.STATUS_SENT);
            int bucket = (int) Math.floorMod(t.receiverId, 64L);
            groups.computeIfAbsent(bucket, k -> new ArrayList<>()).add(inbox);
        }

        for (List<MessageInbox> bucket : groups.values()) {
            try {
                if (bucket.size() == 1) {
                    messageInboxMapper.insert(bucket.get(0));
                } else {
                    messageInboxMapper.insertBatch(bucket);
                }
            } catch (Exception e) {
                log.warn("Batch inbox insert failed size={}, fallback single", bucket.size(), e);
                for (MessageInbox inbox : bucket) {
                    try {
                        messageInboxMapper.insert(inbox);
                    } catch (DuplicateKeyException ignored) {
                    } catch (Exception ex) {
                        log.error("Inbox fallback failed: msgId={} receiver={}",
                                inbox.getMsgId(), inbox.getReceiverId(), ex);
                    }
                }
            }
        }
    }

    private void flushOutboxBatch(List<OutboxTask> tasks) {
        List<MessageOutbox> outboxList = new ArrayList<>(tasks.size());
        List<OutboxPending> pendingList = new ArrayList<>(tasks.size());

        for (OutboxTask t : tasks) {
            long outboxId = IdWorker.getId();

            MessageOutbox outbox = new MessageOutbox();
            outbox.setId(outboxId);
            outbox.setMsgId(t.msgId);
            outbox.setTargetUserId(t.targetUserId);
            outbox.setTopic(t.topic);
            outbox.setConversationId(t.conversationId);
            outbox.setMessageCreatedAt(t.messageCreatedAt);
            outbox.setStatus(MessageOutboxConstants.STATUS_PENDING);
            outbox.setRetryCount(0);
            outbox.setNextRetryAt(LocalDateTime.now());
            outboxList.add(outbox);

            OutboxPending pending = new OutboxPending();
            pending.setOutboxId(outboxId);
            pending.setOutboxShard((int) (t.targetUserId % 8));
            pending.setTargetUserId(t.targetUserId);
            pending.setTopic(t.topic);
            pending.setMsgId(t.msgId);
            pending.setStatus(0);
            pending.setRetryCount(0);
            pending.setNextRetryAt(LocalDateTime.now());
            pendingList.add(pending);
        }

        Map<Long, List<MessageOutbox>> outboxGroups = new LinkedHashMap<>();
        for (MessageOutbox o : outboxList) {
            long key = ((long) (int) Math.floorMod(o.getTargetUserId(), 64L)) << 8
                    | (int) Math.floorMod(o.getTargetUserId(), 8L);
            outboxGroups.computeIfAbsent(key, k -> new ArrayList<>()).add(o);
        }

        for (List<MessageOutbox> bucket : outboxGroups.values()) {
            try {
                if (bucket.size() == 1) {
                    messageOutboxMapper.insert(bucket.get(0));
                } else {
                    messageOutboxMapper.insertBatch(bucket);
                }
            } catch (Exception e) {
                log.warn("Batch outbox insert failed size={}, fallback single", bucket.size(), e);
                for (MessageOutbox o : bucket) {
                    try {
                        messageOutboxMapper.insert(o);
                    } catch (DuplicateKeyException ignored) {
                    } catch (Exception ex) {
                        log.error("Outbox fallback failed: msgId={}", o.getMsgId(), ex);
                    }
                }
            }
        }

        for (OutboxPending p : pendingList) {
            try {
                outboxPendingMapper.insert(p);
                clearReconcile(p.getOutboxId());
            } catch (DuplicateKeyException ignored) {
                clearReconcile(p.getOutboxId());
            } catch (Exception e) {
                queueReconcileFallback(p, e);
            }
        }
    }

    private void syncWriteInbox(InboxTask task) {
        MessageInbox inbox = new MessageInbox();
        inbox.setMsgId(task.msgId);
        inbox.setReceiverId(task.receiverId);
        inbox.setConversationType(task.conversationType);
        inbox.setConversationId(task.conversationId);
        inbox.setStatus(MessageInboxConstants.STATUS_SENT);
        try {
            messageInboxMapper.insert(inbox);
        } catch (DuplicateKeyException ignored) {
        }
    }

    private void syncWriteOutbox(OutboxTask task) {
        long outboxId = IdWorker.getId();

        MessageOutbox outbox = new MessageOutbox();
        outbox.setId(outboxId);
        outbox.setMsgId(task.msgId);
        outbox.setTargetUserId(task.targetUserId);
        outbox.setTopic(task.topic);
        outbox.setConversationId(task.conversationId);
        outbox.setMessageCreatedAt(task.messageCreatedAt);
        outbox.setStatus(MessageOutboxConstants.STATUS_PENDING);
        outbox.setRetryCount(0);
        outbox.setNextRetryAt(LocalDateTime.now());

        try {
            messageOutboxMapper.insert(outbox);
        } catch (DuplicateKeyException ignored) {
        }

        OutboxPending pending = new OutboxPending();
        pending.setOutboxId(outboxId);
        pending.setOutboxShard((int) (task.targetUserId % 8));
        pending.setTargetUserId(task.targetUserId);
        pending.setTopic(task.topic);
        pending.setMsgId(task.msgId);
        pending.setStatus(0);
        pending.setRetryCount(0);
        pending.setNextRetryAt(LocalDateTime.now());

        try {
            outboxPendingMapper.insert(pending);
            clearReconcile(outboxId);
        } catch (DuplicateKeyException ignored) {
            clearReconcile(outboxId);
        } catch (Exception e) {
            queueReconcileFallback(pending, e);
        }
    }

    private void queueReconcileFallback(OutboxPending pending, Exception cause) {
        try {
            Map<String, Object> hash = new java.util.HashMap<>();
            hash.put("outboxId", pending.getOutboxId());
            hash.put("targetUserId", pending.getTargetUserId());
            hash.put("topic", pending.getTopic());
            hash.put("msgId", pending.getMsgId());
            String key = RedisConstants.OUTBOX_RECONCILE_PREFIX + pending.getOutboxId();
            redisTemplate.opsForHash().putAll(key, hash);
            redisTemplate.expire(key, RedisConstants.OUTBOX_RECONCILE_TTL_DAYS, TimeUnit.DAYS);
            redisTemplate.opsForSet().add(RedisConstants.OUTBOX_RECONCILE_SET, pending.getOutboxId().toString());
            log.warn("outbox_pending insert failed, queued to Redis reconcile: outboxId={}", pending.getOutboxId(), cause);
        } catch (Exception redisErr) {
            log.error("CRITICAL: both outbox_pending and Redis reconcile failed: outboxId={}",
                    pending.getOutboxId(), redisErr);
        }
    }

    private void clearReconcile(Long outboxId) {
        try {
            redisTemplate.delete(RedisConstants.OUTBOX_RECONCILE_PREFIX + outboxId);
            redisTemplate.opsForSet().remove(RedisConstants.OUTBOX_RECONCILE_SET, outboxId.toString());
        } catch (Exception ignored) {
        }
    }
}
