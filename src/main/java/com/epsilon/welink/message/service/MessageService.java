package com.epsilon.welink.message.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.epsilon.welink.common.constant.RedisConstants;
import com.epsilon.welink.common.exception.BusinessException;
import com.epsilon.welink.common.result.ResultCode;
import com.epsilon.welink.message.constant.MessageInboxConstants;
import com.epsilon.welink.message.dto.ConversationSummaryDTO;
import com.epsilon.welink.message.dto.MessageContextRequest;
import com.epsilon.welink.message.dto.MessageContextResponse;
import com.epsilon.welink.message.dto.MessageSearchRequest;
import com.epsilon.welink.message.dto.MessageRequest;
import com.epsilon.welink.message.entity.Conversation;
import com.epsilon.welink.message.entity.DeliveryReceipt;
import com.epsilon.welink.message.entity.Message;
import com.epsilon.welink.message.entity.MessageInbox;
import com.epsilon.welink.message.entity.ReadCursor;
import com.epsilon.welink.message.mapper.ConversationMapper;
import com.epsilon.welink.message.mapper.DeliveryReceiptMapper;
import com.epsilon.welink.message.mapper.MessageInboxMapper;
import com.epsilon.welink.message.mapper.MessageMapper;
import com.epsilon.welink.message.mapper.ReadCursorMapper;
import com.epsilon.welink.relation.entity.GroupMember;
import com.epsilon.welink.relation.mapper.GroupMemberMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import jakarta.annotation.PreDestroy;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
public class MessageService {

    private final MessageMapper messageMapper;
    private final MessageInboxMapper messageInboxMapper;
    private final GroupMemberMapper groupMemberMapper;
    private final DeliveryReceiptMapper deliveryReceiptMapper;
    private final ConversationMapper conversationMapper;
    private final ReadCursorMapper readCursorMapper;
    private final RedisTemplate<String, Object> redisTemplate;
    private final AsyncBatchWriter asyncBatchWriter;
    private final java.util.concurrent.LinkedBlockingQueue<MsgBatchItem> messageBatchQueue =
            new java.util.concurrent.LinkedBlockingQueue<>(10000);
    private final java.util.concurrent.ExecutorService convUpdateExecutor =
            java.util.concurrent.Executors.newFixedThreadPool(8, r -> {
                Thread t = new Thread(r, "conv-update");
                t.setDaemon(true);
                return t;
            });

    public MessageService(MessageMapper messageMapper,
                          MessageInboxMapper messageInboxMapper,
                          GroupMemberMapper groupMemberMapper,
                          DeliveryReceiptMapper deliveryReceiptMapper,
                          ConversationMapper conversationMapper,
                          ReadCursorMapper readCursorMapper,
                          RedisTemplate<String, Object> redisTemplate,
                          AsyncBatchWriter asyncBatchWriter) {
        this.messageMapper = messageMapper;
        this.messageInboxMapper = messageInboxMapper;
        this.groupMemberMapper = groupMemberMapper;
        this.deliveryReceiptMapper = deliveryReceiptMapper;
        this.conversationMapper = conversationMapper;
        this.readCursorMapper = readCursorMapper;
        this.redisTemplate = redisTemplate;
        this.asyncBatchWriter = asyncBatchWriter;
    }

    public Message buildAndEnqueuePrivateMessage(Long fromUserId, MessageRequest request,
                                                  Long targetUserId, boolean needsOutbox) {
        Message message = buildPrivateMessageCore(fromUserId, request);
        if (message == null) return null;
        String convKey = buildConversationKey(fromUserId, targetUserId);
        Long convId = buildConversationId(convKey);
        message.setConversationId(convId);
        messageBatchQueue.offer(MsgBatchItem.privateMsg(message, targetUserId, needsOutbox, convId));
        if (messageBatchQueue.size() >= BATCH_FLUSH_THRESHOLD) {
            flushMessageBatch();
        }
        return message;
    }

    public Message buildAndEnqueueGroupMessage(Long fromUserId, MessageRequest request,
                                                Long groupId, List<Long> otherInstanceIds,
                                                List<Long> offlineMemberIds) {
        Message message = buildGroupMessageCore(fromUserId, request, groupId);
        if (message == null) return null;
        String convKey = buildGroupConversationKey(groupId);
        Long convId = buildConversationId(convKey);
        messageBatchQueue.offer(MsgBatchItem.groupMsg(message, otherInstanceIds, offlineMemberIds, convId));
        if (messageBatchQueue.size() >= BATCH_FLUSH_THRESHOLD) {
            flushMessageBatch();
        }
        return message;
    }

    @Scheduled(fixedDelay = 100)
    public void flushMessageBatchScheduled() {
        flushMessageBatch();
    }

    @PreDestroy
    public void drainMessageQueueOnShutdown() {
        int remaining = messageBatchQueue.size();
        if (remaining > 0) {
            log.info("Draining {} remaining message batch items on shutdown", remaining);
            flushMessageBatch();
        }
    }

    private void flushMessageBatch() {
        List<MsgBatchItem> batch = new java.util.ArrayList<>(1000);
        messageBatchQueue.drainTo(batch, 1000);
        if (batch.isEmpty()) return;

        java.util.Map<Integer, java.util.List<Message>> shardGroups = new java.util.LinkedHashMap<>();
        for (MsgBatchItem item : batch) {
            int shard = (int) Math.floorMod(item.message.getConversationId(), 8L);
            shardGroups.computeIfAbsent(shard, k -> new java.util.ArrayList<>()).add(item.message);
        }

        java.util.concurrent.CountDownLatch insertLatch =
                new java.util.concurrent.CountDownLatch(shardGroups.size());
        for (java.util.List<Message> msgs : shardGroups.values()) {
            convUpdateExecutor.submit(() -> {
                try {
                    messageMapper.insertBatch(msgs);
                    for (Message m : msgs) {
                        cacheRecentMessage(m);
                    }
                } catch (Exception e) {
                    log.warn("Batch message insert failed for shard size={}, fallback single", msgs.size(), e);
                    for (Message m : msgs) {
                        try {
                            messageMapper.insert(m);
                            cacheRecentMessage(m);
                        } catch (org.springframework.dao.DuplicateKeyException ignored) {
                        }
                    }
                } finally {
                    insertLatch.countDown();
                }
            });
        }
        try {
            insertLatch.await(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }

        for (MsgBatchItem item : batch) {
            Message m = item.message;
            if (item.isGroup) {
                if (item.offlineMemberIds != null && !item.offlineMemberIds.isEmpty()) {
                    List<AsyncBatchWriter.InboxTask> tasks = new ArrayList<>(item.offlineMemberIds.size());
                    for (Long uid : item.offlineMemberIds) {
                        tasks.add(new AsyncBatchWriter.InboxTask(
                                m.getMsgId(), uid, MessageInboxConstants.CONVERSATION_GROUP, item.convId));
                    }
                    asyncBatchWriter.submitInboxTasks(tasks);
                }
                if (item.otherInstanceIds != null && !item.otherInstanceIds.isEmpty()) {
                    List<AsyncBatchWriter.OutboxTask> tasks = new ArrayList<>(item.otherInstanceIds.size());
                    for (Long uid : item.otherInstanceIds) {
                        tasks.add(new AsyncBatchWriter.OutboxTask(
                                m.getMsgId(), uid, "im-group-message", item.convId,
                                java.time.LocalDateTime.now()));
                    }
                    asyncBatchWriter.submitOutboxTasks(tasks);
                }
            } else {
                asyncBatchWriter.submitInboxTask(new AsyncBatchWriter.InboxTask(
                        m.getMsgId(), item.targetUserId, MessageInboxConstants.CONVERSATION_PRIVATE, item.convId));
                if (item.needsOutbox) {
                    asyncBatchWriter.submitOutboxTask(new AsyncBatchWriter.OutboxTask(
                            m.getMsgId(), item.targetUserId, "im-private-message", item.convId,
                            java.time.LocalDateTime.now()));
                }
            }
        }

        java.util.Map<Long, MsgBatchItem> convLastMap = new java.util.LinkedHashMap<>();
        for (MsgBatchItem item : batch) {
            convLastMap.put(item.convId, item);
        }
        for (MsgBatchItem item : convLastMap.values()) {
            final Message m = item.message;
            convUpdateExecutor.submit(() -> {
                try {
                    if (item.isGroup) {
                        Conversation conv = findOrCreateConversation(BIZ_TYPE_GROUP,
                                buildGroupConversationKey(m.getGroupId()));
                        updateConversationLastMessage(conv.getId(), m.getMsgId(), m.getGroupSeq());
                    } else {
                        Conversation conv = findOrCreateConversation(BIZ_TYPE_SINGLE,
                                buildConversationKey(m.getFromUserId(), item.targetUserId));
                        updateConversationLastMessage(conv.getId(), m.getMsgId(), m.getConversationSeq());
                    }
                } catch (Exception e) {
                    log.warn("Failed to update conversation for msgId={}", m.getMsgId(), e);
                }
            });
        }

        if (!batch.isEmpty()) {
            log.info("[BATCH:message] flushed {} messages", batch.size());
        }
    }

    private static final int BATCH_FLUSH_THRESHOLD = 1000;

    private static class MsgBatchItem {
        final Message message;
        final Long targetUserId;
        final boolean needsOutbox;
        final Long convId;
        final boolean isGroup;
        final List<Long> otherInstanceIds;
        final List<Long> offlineMemberIds;

        static MsgBatchItem privateMsg(Message message, Long targetUserId, boolean needsOutbox, Long convId) {
            return new MsgBatchItem(message, targetUserId, needsOutbox, convId, false, null, null);
        }

        static MsgBatchItem groupMsg(Message message, List<Long> otherInstanceIds,
                                      List<Long> offlineMemberIds, Long convId) {
            return new MsgBatchItem(message, null, false, convId, true, otherInstanceIds, offlineMemberIds);
        }

        private MsgBatchItem(Message message, Long targetUserId, boolean needsOutbox, Long convId,
                             boolean isGroup, List<Long> otherInstanceIds, List<Long> offlineMemberIds) {
            this.message = message;
            this.targetUserId = targetUserId;
            this.needsOutbox = needsOutbox;
            this.convId = convId;
            this.isGroup = isGroup;
            this.otherInstanceIds = otherInstanceIds;
            this.offlineMemberIds = offlineMemberIds;
        }
    }

    private Message buildPrivateMessageCore(Long fromUserId, MessageRequest request) {
        String convKey = buildConversationKey(fromUserId, request.getToUserId());
        Long convId = buildConversationId(convKey);

        String clientMsgId = request.getClientMsgId();
        if (org.springframework.util.StringUtils.hasText(clientMsgId)) {
            String dedupKey = RedisConstants.IM_DEDUP_CLIENT_MSG_PREFIX + fromUserId + ":" + clientMsgId;
            Object cachedMsgId = redisTemplate.opsForValue().get(dedupKey);
            if (cachedMsgId != null) {
                Message existing = getMessageByMsgIdRouted(cachedMsgId.toString(), convId, null);
                if (existing != null) {
                    return existing;
                }
            }
        }

        String msgId = resolveMsgId(request.getMsgId());

        Message message = new Message();
        message.setId(IdWorker.getId());
        message.setMsgId(msgId);
        message.setFromUserId(fromUserId);
        message.setToUserId(request.getToUserId());
        message.setConversationSeq(allocateConversationSeq(fromUserId, request.getToUserId()));
        message.setConversationId(convId);
        message.setMsgType(request.getMsgType() != null ? request.getMsgType() : 1);
        message.setContent(request.getContent());
        message.setStatus(MessageInboxConstants.STATUS_SENT);

        if (org.springframework.util.StringUtils.hasText(clientMsgId)) {
            String dedupKey = RedisConstants.IM_DEDUP_CLIENT_MSG_PREFIX + fromUserId + ":" + clientMsgId;
            redisTemplate.opsForValue().set(dedupKey, msgId, 24, java.util.concurrent.TimeUnit.HOURS);
        }

        return message;
    }

    private Message buildGroupMessageCore(Long fromUserId, MessageRequest request, Long groupId) {
        String convKey = buildGroupConversationKey(groupId);
        Long convId = buildConversationId(convKey);

        String clientMsgId = request.getClientMsgId();
        if (org.springframework.util.StringUtils.hasText(clientMsgId)) {
            String dedupKey = RedisConstants.IM_DEDUP_CLIENT_MSG_PREFIX + fromUserId + ":" + clientMsgId;
            Object cachedMsgId = redisTemplate.opsForValue().get(dedupKey);
            if (cachedMsgId != null) {
                Message existing = getMessageByMsgIdRouted(cachedMsgId.toString(), convId, null);
                if (existing != null) {
                    return existing;
                }
            }
        }

        String msgId = resolveMsgId(request.getMsgId());
        if (org.springframework.util.StringUtils.hasText(request.getMsgId())) {
            Message existing = getMessageByMsgIdRouted(msgId, convId, null);
            if (existing != null) {
                cacheRecentMessage(existing);
                return existing;
            }
        }

        Message message = new Message();
        message.setId(IdWorker.getId());
        message.setMsgId(msgId);
        message.setFromUserId(fromUserId);
        message.setGroupId(groupId);
        message.setGroupSeq(allocateGroupSeq(groupId));
        message.setConversationId(convId);
        message.setMsgType(request.getMsgType() != null ? request.getMsgType() : 1);
        message.setContent(request.getContent());
        message.setStatus(MessageInboxConstants.STATUS_SENT);

        if (org.springframework.util.StringUtils.hasText(clientMsgId)) {
            String dedupKey = RedisConstants.IM_DEDUP_CLIENT_MSG_PREFIX + fromUserId + ":" + clientMsgId;
            redisTemplate.opsForValue().set(dedupKey, msgId, 24, java.util.concurrent.TimeUnit.HOURS);
        }
        markGroupMemberReadSeq(fromUserId, groupId, message.getGroupSeq());

        return message;
    }

    /**
     * 持久化私聊消息: Phase1 同步落 message + conversation 并立即返回给调用方推送;
     * inbox/outbox 投递到 AsyncBatchWriter 后台批量写入, 保证实时性.
     */
    @Transactional
    public Message sendPrivateMessageOutboxed(Long fromUserId, MessageRequest request, Long targetUserId, boolean needsOutbox) {
        Message saved = savePrivateMessage(fromUserId, request);
        String convKey = buildConversationKey(fromUserId, targetUserId);
        Long convId = buildConversationId(convKey);

        asyncBatchWriter.submitInboxTask(new AsyncBatchWriter.InboxTask(
                saved.getMsgId(), targetUserId, MessageInboxConstants.CONVERSATION_PRIVATE, convId));
        if (needsOutbox) {
            asyncBatchWriter.submitOutboxTask(new AsyncBatchWriter.OutboxTask(
                    saved.getMsgId(), targetUserId, "im-private-message", convId, saved.getCreatedAt()));
        }

        Conversation conv = findOrCreateConversation(BIZ_TYPE_SINGLE, convKey);
        updateConversationLastMessage(conv.getId(), saved.getMsgId(), saved.getConversationSeq());
        return saved;
    }

    /** 兼容旧名, 内部转调新方法. 后续调用方迁移完毕后可移除. */
    @Deprecated
    @Transactional
    public Message sendPrivateMessageTransactional(Long fromUserId, MessageRequest request, Long targetUserId, boolean needsOutbox) {
        return sendPrivateMessageOutboxed(fromUserId, request, targetUserId, needsOutbox);
    }

    public static final int BIZ_TYPE_SINGLE = 1;
    public static final int BIZ_TYPE_GROUP = 2;

    public static String buildConversationKey(Long userA, Long userB) {
        long min = Math.min(userA, userB);
        long max = Math.max(userA, userB);
        return "single:" + min + ":" + max;
    }

    public static String buildGroupConversationKey(Long groupId) {
        return "group:" + groupId;
    }

    public static Long buildConversationId(String convKey) {
        try {
            byte[] md5 = MessageDigest.getInstance("MD5").digest(convKey.getBytes(StandardCharsets.UTF_8));
            long high = 0;
            for (int i = 0; i < 8; i++) {
                high = (high << 8) | (md5[i] & 0xFF);
            }
            return high & Long.MAX_VALUE;
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 not available", e);
        }
    }

    public Conversation findOrCreateConversation(int bizType, String ownerKey) {
        Long cachedConversationId = getCachedConversationId(bizType, ownerKey);
        if (cachedConversationId != null) {
            Conversation cached = new Conversation();
            cached.setId(cachedConversationId);
            cached.setBizType(bizType);
            cached.setOwnerKey(ownerKey);
            return cached;
        }
        Conversation conv = conversationMapper.selectOne(new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getBizType, bizType)
                .eq(Conversation::getOwnerKey, ownerKey));
        if (conv != null) {
            cacheConversationId(conv);
            return conv;
        }
        conv = new Conversation();
        conv.setBizType(bizType);
        conv.setOwnerKey(ownerKey);
        conv.setLastSeq(0L);
        long now = System.currentTimeMillis();
        conv.setLastMessageAt(now);
        conv.setCreatedAt(java.time.LocalDateTime.now());
        try {
            conversationMapper.insert(conv);
        } catch (DuplicateKeyException e) {
            Conversation existing = conversationMapper.selectOne(new LambdaQueryWrapper<Conversation>()
                    .eq(Conversation::getBizType, bizType)
                    .eq(Conversation::getOwnerKey, ownerKey));
            cacheConversationId(existing);
            return existing;
        }
        cacheConversationId(conv);
        return conv;
    }

    private Long getCachedConversationId(int bizType, String ownerKey) {
        try {
            Object cached = redisTemplate.opsForValue().get(buildConversationCacheKey(bizType, ownerKey));
            if (cached instanceof Number number) {
                return number.longValue();
            }
            if (cached instanceof String text && StringUtils.hasText(text)) {
                return Long.parseLong(text);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void cacheConversationId(Conversation conversation) {
        if (conversation == null || conversation.getId() == null || !StringUtils.hasText(conversation.getOwnerKey())) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(
                    buildConversationCacheKey(conversation.getBizType(), conversation.getOwnerKey()),
                    conversation.getId(),
                    RedisConstants.CONVERSATION_ID_CACHE_TTL_DAYS,
                    TimeUnit.DAYS
            );
        } catch (Exception ignored) {
        }
    }

    private String buildConversationCacheKey(int bizType, String ownerKey) {
        return RedisConstants.IM_CONVERSATION_ID_PREFIX + bizType + ":" + ownerKey;
    }

    private void updateConversationLastMessage(Long conversationId, String msgId, Long seq) {
        long now = System.currentTimeMillis();
        conversationMapper.update(null, new LambdaUpdateWrapper<Conversation>()
                .eq(Conversation::getId, conversationId)
                .set(Conversation::getLastMsgId, msgId)
                .set(Conversation::getLastSeq, seq)
                .set(Conversation::getLastMessageAt, now));
    }

    public Message savePrivateMessage(Long fromUserId, MessageRequest request) {
        return saveMessageCore(fromUserId, request, true);
    }

    private Message saveMessageCore(Long fromUserId, MessageRequest request, boolean isPrivate) {
        // 提前计算 conversationId, 用于后续所有 message 查询的路由
        String convKey = isPrivate
                ? buildConversationKey(fromUserId, request.getToUserId())
                : buildGroupConversationKey(request.getGroupId());
        Long convId = buildConversationId(convKey);

        String clientMsgId = request.getClientMsgId();
        if (StringUtils.hasText(clientMsgId)) {
            String dedupKey = RedisConstants.IM_DEDUP_CLIENT_MSG_PREFIX + fromUserId + ":" + clientMsgId;
            Object cachedMsgId = redisTemplate.opsForValue().get(dedupKey);
            if (cachedMsgId != null) {
                // 已知 convId, 走路由查询; 缓存未命中也只扫单分片
                Message existing = getMessageByMsgIdRouted(cachedMsgId.toString(), convId, null);
                if (existing != null) {
                    return existing;
                }
            }
        }

        String msgId = resolveMsgId(request.getMsgId());
        if (!isPrivate && StringUtils.hasText(request.getMsgId())) {
            Message existing = getMessageByMsgIdRouted(msgId, convId, null);
            if (existing != null) {
                cacheRecentMessage(existing);
                return existing;
            }
        }

        Message message = new Message();
        message.setMsgId(msgId);
        message.setFromUserId(fromUserId);
        message.setToUserId(isPrivate ? request.getToUserId() : null);
        message.setGroupId(isPrivate ? null : request.getGroupId());
        message.setGroupSeq(isPrivate ? null : allocateGroupSeq(request.getGroupId()));
        message.setConversationSeq(isPrivate ? allocateConversationSeq(fromUserId, request.getToUserId()) : null);
        message.setConversationId(convId);
        message.setMsgType(request.getMsgType() != null ? request.getMsgType() : 1);
        message.setContent(request.getContent());
        message.setStatus(MessageInboxConstants.STATUS_SENT);

        try {
            messageMapper.insert(message);
            ensureCreatedAt(message);
            cacheRecentMessage(message);
            if (StringUtils.hasText(clientMsgId)) {
                String dedupKey = RedisConstants.IM_DEDUP_CLIENT_MSG_PREFIX + fromUserId + ":" + clientMsgId;
                redisTemplate.opsForValue().set(dedupKey, msgId, 24, TimeUnit.HOURS);
            }
            if (!isPrivate) {
                markGroupMemberReadSeq(fromUserId, request.getGroupId(), message.getGroupSeq());
            }
            return message;
        } catch (DuplicateKeyException e) {
            Message duplicate = getMessageByMsgIdRouted(msgId, convId, null);
            if (duplicate != null) {
                cacheRecentMessage(duplicate);
            }
            return duplicate;
        }
    }

    private String resolveMsgId(String msgId) {
        return StringUtils.hasText(msgId) ? msgId.trim() : UUID.randomUUID().toString();
    }

    public void markDelivered(String msgId, Long receiverId) {
        messageInboxMapper.update(
                null,
                new LambdaUpdateWrapper<MessageInbox>()
                        .eq(MessageInbox::getMsgId, msgId)
                        .eq(MessageInbox::getReceiverId, receiverId)
                        .lt(MessageInbox::getStatus, MessageInboxConstants.STATUS_DELIVERED)
                        .set(MessageInbox::getStatus, MessageInboxConstants.STATUS_DELIVERED)
                        .set(MessageInbox::getUpdatedAt, LocalDateTime.now())
        );
        upsertDeliveryReceipt(msgId, receiverId, MessageInboxConstants.STATUS_DELIVERED);
    }

    public Page<Message> getPrivateHistory(Long userId, Long targetId, Integer pageNum, Integer pageSize) {
        Long conversationId = buildConversationId(buildConversationKey(userId, targetId));
        String cacheKey = buildPrivateRecentKey(userId, targetId);
        LambdaQueryWrapper<Message> baseQuery = buildPrivateHistoryQuery(userId, targetId, conversationId);
        return getHistoryWithRecentCache(cacheKey, baseQuery, pageNum, pageSize);
    }

    public Page<Message> getGroupHistory(Long groupId, Integer pageNum, Integer pageSize) {
        Long conversationId = buildConversationId(buildGroupConversationKey(groupId));
        String cacheKey = buildGroupRecentKey(groupId);
        LambdaQueryWrapper<Message> baseQuery = buildGroupHistoryQuery(groupId, conversationId);
        return getHistoryWithRecentCache(cacheKey, baseQuery, pageNum, pageSize);
    }

    public Page<Message> searchMessages(Long userId, MessageSearchRequest request) {
        if (request == null || request.getConversationType() == null || request.getTargetId() == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "会话类型和目标会话不能为空");
        }

        if (request.getStartTime() != null && request.getEndTime() != null
                && request.getStartTime() > request.getEndTime()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "开始时间不能晚于结束时间");
        }

        int safePageNum = request.getPageNum() != null && request.getPageNum() > 0 ? request.getPageNum() : 1;
        int safePageSize = request.getPageSize() != null && request.getPageSize() > 0 ? request.getPageSize() : 20;

        ConversationSearchScope scope = resolveConversationSearchScope(userId, request.getConversationType(), request.getTargetId());
        LambdaQueryWrapper<Message> queryWrapper = scope.baseQuery();

        applyMessageSearchFilters(queryWrapper, request);
        queryWrapper.orderByDesc(Message::getCreatedAt).orderByDesc(Message::getId);

        return messageMapper.selectPage(new Page<>(safePageNum, safePageSize), queryWrapper);
    }

    public MessageContextResponse getMessageContext(Long userId, MessageContextRequest request) {
        if (request == null || request.getConversationType() == null || request.getTargetId() == null
                || !StringUtils.hasText(request.getMsgId())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "会话信息和消息ID不能为空");
        }

        int beforeLimit = request.getBeforeLimit() != null && request.getBeforeLimit() > 0 ? request.getBeforeLimit() : 15;
        int afterLimit = request.getAfterLimit() != null && request.getAfterLimit() > 0 ? request.getAfterLimit() : 15;

        ConversationSearchScope scope = resolveConversationSearchScope(userId, request.getConversationType(), request.getTargetId());
        Message anchor = getAnchorMessage(scope, request.getMsgId());
        if (anchor == null
                || !Objects.equals(anchor.getConversationId(), scope.conversationId())
                || (scope.groupId() != null && !Objects.equals(anchor.getGroupId(), scope.groupId()))) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "消息不存在或不属于当前会话");
        }

        List<Message> before = loadContextMessages(scope, anchor, beforeLimit, true);
        Collections.reverse(before);
        List<Message> after = loadContextMessages(scope, anchor, afterLimit, false);

        List<Message> merged = new ArrayList<>(before.size() + after.size() + 1);
        merged.addAll(before);
        merged.add(anchor);
        merged.addAll(after);

        MessageContextResponse response = new MessageContextResponse();
        response.setAnchorMsgId(anchor.getMsgId());
        response.setRecords(merged);
        return response;
    }

    public List<ConversationSummaryDTO> getConversationSummaries(Long userId) {
        LambdaQueryWrapper<MessageInbox> inboxQuery = new LambdaQueryWrapper<>();
        inboxQuery.eq(MessageInbox::getReceiverId, userId)
                .lt(MessageInbox::getStatus, MessageInboxConstants.STATUS_READ)
                .orderByAsc(MessageInbox::getCreatedAt);
        List<MessageInbox> inboxList = messageInboxMapper.selectList(inboxQuery);

        List<String> msgIds = inboxList.stream()
                .map(MessageInbox::getMsgId)
                .distinct()
                .toList();
        Map<String, Message> messageMap = loadMessagesByMsgIdsPreferCache(msgIds);

        Map<String, ConversationSummaryDTO> summaryMap = new LinkedHashMap<>();
        for (MessageInbox inbox : inboxList) {
            Message message = messageMap.get(inbox.getMsgId());
            if (message == null) {
                continue;
            }

            Integer conversationType = inbox.getConversationType();
            Long targetId = resolveConversationTargetId(userId, message, conversationType);
            if (targetId == null) {
                continue;
            }

            String summaryKey = conversationType + "_" + targetId;
            ConversationSummaryDTO summary = summaryMap.computeIfAbsent(summaryKey, key -> {
                ConversationSummaryDTO dto = new ConversationSummaryDTO();
                dto.setConversationType(conversationType);
                dto.setTargetId(targetId);
                dto.setUnreadCount(0);
                return dto;
            });

            summary.setUnreadCount(summary.getUnreadCount() + 1);
            if (summary.getLastTime() == null || message.getCreatedAt().isAfter(summary.getLastTime())) {
                summary.setLastTime(message.getCreatedAt());
                summary.setLastMessage(message.getContent());
            }
        }

        List<GroupMember> groupMemberships = groupMemberMapper.selectList(new LambdaQueryWrapper<GroupMember>()
                .eq(GroupMember::getUserId, userId));
        for (GroupMember membership : groupMemberships) {
            Message latestGroupMessage = getLatestGroupMessage(membership.getGroupId());
            if (latestGroupMessage == null) {
                continue;
            }

            String summaryKey = MessageInboxConstants.CONVERSATION_GROUP + "_" + membership.getGroupId();
            ConversationSummaryDTO summary = summaryMap.computeIfAbsent(summaryKey, key -> {
                ConversationSummaryDTO dto = new ConversationSummaryDTO();
                dto.setConversationType(MessageInboxConstants.CONVERSATION_GROUP);
                dto.setTargetId(membership.getGroupId());
                dto.setUnreadCount(0);
                return dto;
            });

            long lastReadSeq = membership.getLastReadSeq() == null ? 0L : membership.getLastReadSeq();
            long latestSeq = latestGroupMessage.getGroupSeq() == null ? 0L : latestGroupMessage.getGroupSeq();
            summary.setUnreadCount((int) Math.max(latestSeq - lastReadSeq, 0L));
            if (summary.getLastTime() == null || latestGroupMessage.getCreatedAt().isAfter(summary.getLastTime())) {
                summary.setLastTime(latestGroupMessage.getCreatedAt());
                summary.setLastMessage(latestGroupMessage.getContent());
            }
        }

        return summaryMap.values().stream()
                .sorted(Comparator.comparing(ConversationSummaryDTO::getLastTime,
                        Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                .toList();
    }

    @Transactional
    public List<Message> getOfflineMessages(Long userId) {
        LambdaQueryWrapper<MessageInbox> inboxQuery = new LambdaQueryWrapper<>();
        inboxQuery.eq(MessageInbox::getReceiverId, userId)
                .lt(MessageInbox::getStatus, MessageInboxConstants.STATUS_READ)
                .orderByAsc(MessageInbox::getCreatedAt);
        List<MessageInbox> inboxList = messageInboxMapper.selectList(inboxQuery);
        if (inboxList.isEmpty()) {
            return List.of();
        }

        List<String> orderedMsgIds = inboxList.stream()
                .map(MessageInbox::getMsgId)
                .distinct()
                .toList();

        Map<String, Message> messageMap = loadMessagesByMsgIdsPreferCache(orderedMsgIds);

        List<Message> result = new ArrayList<>();
        for (MessageInbox inbox : inboxList) {
            Message message = messageMap.get(inbox.getMsgId());
            if (message != null) {
                result.add(message);
            }
            if (inbox.getStatus() != null && inbox.getStatus() == MessageInboxConstants.STATUS_SENT) {
                // 不能用 updateById，否则会尝试更新 receiver_id 这个分片键。
                messageInboxMapper.update(null, new LambdaUpdateWrapper<MessageInbox>()
                        .eq(MessageInbox::getId, inbox.getId())
                        .eq(MessageInbox::getReceiverId, inbox.getReceiverId())
                        .lt(MessageInbox::getStatus, MessageInboxConstants.STATUS_DELIVERED)
                        .set(MessageInbox::getStatus, MessageInboxConstants.STATUS_DELIVERED)
                        .set(MessageInbox::getUpdatedAt, LocalDateTime.now()));
            }
        }
        result.sort(Comparator.comparing(Message::getCreatedAt));
        return result;
    }

    @Transactional
    public void markConversationAsRead(Long receiverId, Integer conversationType, Long targetId) {
        if (conversationType != null && conversationType.equals(MessageInboxConstants.CONVERSATION_GROUP)) {
            markGroupConversationAsRead(receiverId, targetId);
            return;
        }
        if (conversationType == null || targetId == null) {
            return;
        }

        Long conversationId = null;
        if (conversationType.equals(MessageInboxConstants.CONVERSATION_PRIVATE)) {
            conversationId = buildConversationId(buildConversationKey(receiverId, targetId));
        }
        if (conversationId == null) {
            return;
        }
        // 私聊整会话已读直接按 receiver_id + conversation_id 精确更新, 避免扫描该用户全部未读私聊.
        messageInboxMapper.update(null, new LambdaUpdateWrapper<MessageInbox>()
                .eq(MessageInbox::getReceiverId, receiverId)
                .eq(MessageInbox::getConversationType, conversationType)
                .eq(MessageInbox::getConversationId, conversationId)
                .lt(MessageInbox::getStatus, MessageInboxConstants.STATUS_READ)
                .set(MessageInbox::getStatus, MessageInboxConstants.STATUS_READ)
                .set(MessageInbox::getUpdatedAt, LocalDateTime.now()));
    }

    public void markAsRead(String msgId, Long receiverId) {
        MessageInbox inbox = messageInboxMapper.selectOne(new LambdaQueryWrapper<MessageInbox>()
                .eq(MessageInbox::getMsgId, msgId)
                .eq(MessageInbox::getReceiverId, receiverId));
        if (inbox == null) {
            return;
        }

        if (inbox.getStatus() != null && inbox.getStatus() >= MessageInboxConstants.STATUS_READ) {
            return;
        }

        // 不能用 updateById (会 SET receiver_id 分片键): 显式 LambdaUpdateWrapper
        messageInboxMapper.update(null, new LambdaUpdateWrapper<MessageInbox>()
                .eq(MessageInbox::getId, inbox.getId())
                .eq(MessageInbox::getReceiverId, receiverId)
                .set(MessageInbox::getStatus, MessageInboxConstants.STATUS_READ)
                .set(MessageInbox::getUpdatedAt, LocalDateTime.now()));
        upsertDeliveryReceipt(msgId, receiverId, MessageInboxConstants.STATUS_READ);

        // 先用 inbox 的路由信息精确路由消息查询 (inbox 已按 receiver_id 单分片命中)
        Message msg = getMessageByMsgIdRouted(msgId, inbox.getConversationId(), null);
        if (msg != null && msg.getConversationSeq() != null) {
            String convKey = msg.getToUserId() != null
                    ? buildConversationKey(msg.getFromUserId(), msg.getToUserId())
                    : buildGroupConversationKey(msg.getGroupId());
            upsertReadCursor(receiverId, convKey, msg.getConversationSeq());
        }
    }

    private void upsertDeliveryReceipt(String msgId, Long receiverId, int status) {
        DeliveryReceipt receipt = new DeliveryReceipt();
        receipt.setMsgId(msgId);
        receipt.setReceiverId(receiverId);
        receipt.setStatus(status);
        if (status == MessageInboxConstants.STATUS_DELIVERED) {
            receipt.setDeliveredAt(LocalDateTime.now());
        }
        if (status == MessageInboxConstants.STATUS_READ) {
            receipt.setReadAt(LocalDateTime.now());
        }
        try {
            deliveryReceiptMapper.insert(receipt);
        } catch (DuplicateKeyException ignored) {
            DeliveryReceipt existing = deliveryReceiptMapper.selectOne(new LambdaQueryWrapper<DeliveryReceipt>()
                    .eq(DeliveryReceipt::getMsgId, msgId)
                    .eq(DeliveryReceipt::getReceiverId, receiverId));
            if (existing != null && (existing.getStatus() == null || existing.getStatus() < status)) {
                existing.setStatus(status);
                if (status == MessageInboxConstants.STATUS_DELIVERED && existing.getDeliveredAt() == null) {
                    existing.setDeliveredAt(LocalDateTime.now());
                }
                if (status == MessageInboxConstants.STATUS_READ && existing.getReadAt() == null) {
                    existing.setReadAt(LocalDateTime.now());
                }
                deliveryReceiptMapper.updateById(existing);
            }
        }
    }

    private void upsertReadCursor(Long userId, String conversationKey, Long seq) {
        ReadCursor cursor = readCursorMapper.selectOne(new LambdaQueryWrapper<ReadCursor>()
                .eq(ReadCursor::getUserId, userId)
                .eq(ReadCursor::getConversationKey, conversationKey));
        if (cursor == null) {
            cursor = new ReadCursor();
            cursor.setUserId(userId);
            cursor.setConversationKey(conversationKey);
            cursor.setReadSeq(seq);
            cursor.setUpdatedAt(System.currentTimeMillis());
            try {
                readCursorMapper.insert(cursor);
            } catch (DuplicateKeyException ignored) {
            }
        } else if (cursor.getReadSeq() == null || seq > cursor.getReadSeq()) {
            cursor.setReadSeq(seq);
            cursor.setUpdatedAt(System.currentTimeMillis());
            readCursorMapper.updateById(cursor);
        }
    }

    public Message getMessageByMsgId(String msgId) {
        // 1) 优先走 Redis 详情缓存（写入时由 cacheMessageDetail 设置，TTL 8 天）
        Object cached = redisTemplate.opsForValue().get(buildMessageDetailKey(msgId));
        if (cached instanceof Message m) {
            return m;
        }
        // 2) 缓存未命中: 退回全分片扫描（极少触发, 仅过期/重启冷读场景）
        // 调用方如果掌握 conversationId+createdAt, 应改用 getMessageByMsgIdRouted 精确路由
        Message fromDb = messageMapper.selectOne(
                new LambdaQueryWrapper<Message>().eq(Message::getMsgId, msgId)
        );
        if (fromDb != null) {
            cacheMessageDetail(fromDb);
        }
        return fromDb;
    }

    /**
     * 路由版本: 调用方已知 conversation_id + created_at, 单分片单月表精确查询
     * 优先 Redis 缓存, 命中即返回. 缓存未命中走单分片 DB 查询.
     */
    public Message getMessageByMsgIdRouted(String msgId, Long conversationId, LocalDateTime createdAt) {
        Object cached = redisTemplate.opsForValue().get(buildMessageDetailKey(msgId));
        if (cached instanceof Message m) {
            return m;
        }
        LambdaQueryWrapper<Message> query = new LambdaQueryWrapper<Message>()
                .eq(Message::getMsgId, msgId);
        if (conversationId != null) {
            query.eq(Message::getConversationId, conversationId);
        }
        if (createdAt != null) {
            query.eq(Message::getCreatedAt, createdAt);
        }
        Message fromDb = messageMapper.selectOne(query);
        if (fromDb != null) {
            cacheMessageDetail(fromDb);
        }
        return fromDb;
    }

    public void recallMessage(Message message) {
        // 不能用 updateById, MyBatis-Plus 只在 WHERE 里放 id, 没有分片键
        // ShardingSphere 必须广播到所有分库分表; 显式带 conversation_id + created_at 单分片路由
        messageMapper.update(null,
                new LambdaUpdateWrapper<Message>()
                        .eq(Message::getId, message.getId())
                        .eq(Message::getConversationId, message.getConversationId())
                        .eq(Message::getCreatedAt, message.getCreatedAt())
                        .set(Message::getStatus, message.getStatus())
                        .set(Message::getContent, message.getContent())
                        .set(Message::getMsgType, message.getMsgType()));
    }

    public List<Message> getMessagesByConversationAndCursor(String conversationKey, Long cursor, int limit) {
        String[] parts = conversationKey.split(":", 2);
        if (parts.length < 2) return List.of();
        String type = parts[0];

        LambdaQueryWrapper<Message> query = new LambdaQueryWrapper<>();
        if ("single".equals(type)) {
            // 必须带 conversation_id 才能精确路由到单分片, 否则 ShardingSphere 全分片扫描
            Long convId = buildConversationId(conversationKey);
            query.eq(Message::getConversationId, convId)
                 .gt(Message::getConversationSeq, cursor)
                 .isNotNull(Message::getConversationSeq)
                 .orderByAsc(Message::getConversationSeq);
        } else if ("group".equals(type)) {
            // group 路径同样补 conversation_id, group_id 不是分片键
            Long convId = buildConversationId(conversationKey);
            Long groupId = Long.parseLong(parts[1]);
            query.eq(Message::getConversationId, convId)
                 .eq(Message::getGroupId, groupId)
                 .gt(Message::getGroupSeq, cursor)
                 .orderByAsc(Message::getGroupSeq);
        } else {
            return List.of();
        }
        query.last("limit " + limit);
        return messageMapper.selectList(query);
    }

    private Page<Message> getHistoryWithRecentCache(String cacheKey,
                                                    LambdaQueryWrapper<Message> baseQuery,
                                                    Integer pageNum,
                                                    Integer pageSize) {
        int safePageNum = pageNum != null && pageNum > 0 ? pageNum : 1;
        int safePageSize = pageSize != null && pageSize > 0 ? pageSize : 50;
        warmRecentCacheIfNeeded(cacheKey, baseQuery);

        long cacheCount = getRecentCacheCount(cacheKey);
        LocalDateTime oldestCachedTime = getOldestCachedTime(cacheKey, cacheCount);

        int start = (safePageNum - 1) * safePageSize;
        List<Message> records = new ArrayList<>();
        boolean hasMore;

        if (start < cacheCount) {
            int cacheEnd = (int) Math.min(start + safePageSize - 1L, cacheCount - 1);
            records.addAll(getRecentMessagesFromCache(cacheKey, start, cacheEnd));
            int remaining = safePageSize - records.size();
            hasMore = start + records.size() < cacheCount;
            if (!hasMore && remaining > 0) {
                SliceResult<Message> olderSlice = queryOlderMessagesSlice(baseQuery, oldestCachedTime, 0, remaining);
                records.addAll(olderSlice.records());
                hasMore = olderSlice.hasMore();
            }
        } else {
            int olderOffset = (int) (start - cacheCount);
            SliceResult<Message> olderSlice = queryOlderMessagesSlice(baseQuery, oldestCachedTime, olderOffset, safePageSize);
            records.addAll(olderSlice.records());
            hasMore = olderSlice.hasMore();
        }

        Page<Message> page = new Page<>(safePageNum, safePageSize);
        page.setRecords(records);
        page.setTotal(hasMore ? start + records.size() + 1L : start + records.size());
        return page;
    }

    private void ensureGroupMember(Long userId, Long groupId) {
        Long count = groupMemberMapper.selectCount(new LambdaQueryWrapper<GroupMember>()
                .eq(GroupMember::getUserId, userId)
                .eq(GroupMember::getGroupId, groupId));
        if (count == null || count == 0) {
            throw new BusinessException(ResultCode.GROUP_NOT_MEMBER, "不是该群成员，不能搜索群聊记录");
        }
    }

    private void applyMessageSearchFilters(LambdaQueryWrapper<Message> queryWrapper, MessageSearchRequest request) {
        if (StringUtils.hasText(request.getKeyword())) {
            queryWrapper.like(Message::getContent, request.getKeyword().trim());
        }
        if (request.getMsgType() != null) {
            queryWrapper.eq(Message::getMsgType, request.getMsgType());
        }
        if (request.getStartTime() != null) {
            queryWrapper.ge(Message::getCreatedAt, toLocalDateTime(request.getStartTime()));
        }
        if (request.getEndTime() != null) {
            queryWrapper.le(Message::getCreatedAt, toLocalDateTime(request.getEndTime()));
        }
    }

    private Long resolveConversationTargetId(Long userId, Message message, Integer conversationType) {
        if (message == null || conversationType == null) {
            return null;
        }
        if (conversationType.equals(MessageInboxConstants.CONVERSATION_PRIVATE)) {
            return userId.equals(message.getFromUserId()) ? message.getToUserId() : message.getFromUserId();
        }
        if (conversationType.equals(MessageInboxConstants.CONVERSATION_GROUP)) {
            return message.getGroupId();
        }
        return null;
    }

    private boolean belongsToConversation(Long receiverId, Integer conversationType, Long targetId, Message message) {
        if (message == null || conversationType == null || targetId == null) {
            return false;
        }
        if (conversationType.equals(MessageInboxConstants.CONVERSATION_PRIVATE)) {
            return targetId.equals(message.getFromUserId()) || targetId.equals(message.getToUserId());
        }
        if (conversationType.equals(MessageInboxConstants.CONVERSATION_GROUP)) {
            return targetId.equals(message.getGroupId());
        }
        return false;
    }

    private void cacheRecentMessage(Message message) {
        if (message == null || message.getCreatedAt() == null) {
            return;
        }
        cacheMessageDetail(message);
        if (message.getGroupId() != null) {
            addMessageToRecentCache(buildGroupRecentKey(message.getGroupId()), message);
            return;
        }
        if (message.getFromUserId() != null && message.getToUserId() != null) {
            addMessageToRecentCache(buildPrivateRecentKey(message.getFromUserId(), message.getToUserId()), message);
        }
    }

    private void addMessageToRecentCache(String cacheKey, Message message) {
        long score = toEpochMilli(message.getCreatedAt());
        long cutoffScore = toEpochMilli(LocalDateTime.now().minusDays(RedisConstants.RECENT_MESSAGE_CACHE_DAYS));
        redisTemplate.opsForZSet().add(cacheKey, message, score);
        redisTemplate.opsForZSet().removeRangeByScore(cacheKey, Double.NEGATIVE_INFINITY, cutoffScore - 1);
        redisTemplate.expire(cacheKey, RedisConstants.RECENT_MESSAGE_CACHE_TTL_DAYS, TimeUnit.DAYS);
    }

    private void cacheMessageDetail(Message message) {
        redisTemplate.opsForValue().set(
                buildMessageDetailKey(message.getMsgId()),
                message,
                RedisConstants.RECENT_MESSAGE_CACHE_TTL_DAYS,
                TimeUnit.DAYS
        );
    }

    private void warmRecentCacheIfNeeded(String cacheKey, LambdaQueryWrapper<Message> baseQuery) {
        Long cacheCount = redisTemplate.opsForZSet().zCard(cacheKey);
        if (cacheCount != null && cacheCount > 0) {
            return;
        }

        String warmLockKey = cacheKey + ":warm:lock";
        String warmLockValue = UUID.randomUUID().toString();
        Boolean locked = redisTemplate.opsForValue().setIfAbsent(warmLockKey, warmLockValue, 15, TimeUnit.SECONDS);
        if (!Boolean.TRUE.equals(locked)) {
            waitForRecentCacheWarmup(cacheKey);
            return;
        }

        try {
            Long latestCacheCount = redisTemplate.opsForZSet().zCard(cacheKey);
            if (latestCacheCount != null && latestCacheCount > 0) {
                return;
            }

            LocalDateTime recentThreshold = LocalDateTime.now().minusDays(RedisConstants.RECENT_MESSAGE_CACHE_DAYS);
            LambdaQueryWrapper<Message> recentQuery = cloneQuery(baseQuery)
                    .ge(Message::getCreatedAt, recentThreshold)
                    .orderByAsc(Message::getCreatedAt);
            List<Message> recentMessages = messageMapper.selectList(recentQuery);
            for (Message message : recentMessages) {
                ensureCreatedAt(message);
                addMessageToRecentCache(cacheKey, message);
                cacheMessageDetail(message);
            }
        } finally {
            Object currentLockValue = redisTemplate.opsForValue().get(warmLockKey);
            if (warmLockValue.equals(currentLockValue)) {
                redisTemplate.delete(warmLockKey);
            }
        }
    }

    private long getRecentCacheCount(String cacheKey) {
        Long count = redisTemplate.opsForZSet().zCard(cacheKey);
        return count == null ? 0L : count;
    }

    private List<Message> getRecentMessagesFromCache(String cacheKey, long start, long end) {
        if (end < start || start < 0) {
            return List.of();
        }
        Set<Object> cached = redisTemplate.opsForZSet().reverseRange(cacheKey, start, end);
        return convertCachedMessages(cached);
    }

    private LocalDateTime getOldestCachedTime(String cacheKey, long cacheCount) {
        if (cacheCount <= 0) {
            return null;
        }
        Set<Object> cached = redisTemplate.opsForZSet().range(cacheKey, 0, 0);
        if (cached == null || cached.isEmpty()) {
            return null;
        }
        for (Object item : cached) {
            if (item instanceof Message message) {
                ensureCreatedAt(message);
                return message.getCreatedAt();
            }
        }
        return null;
    }

    private void waitForRecentCacheWarmup(String cacheKey) {
        for (int i = 0; i < 5; i++) {
            Long cacheCount = redisTemplate.opsForZSet().zCard(cacheKey);
            if (cacheCount != null && cacheCount > 0) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private List<Message> convertCachedMessages(Collection<Object> cachedObjects) {
        if (cachedObjects == null || cachedObjects.isEmpty()) {
            return List.of();
        }
        List<Message> messages = new ArrayList<>();
        for (Object cached : cachedObjects) {
            if (cached instanceof Message message) {
                messages.add(message);
            }
        }
        messages.sort(Comparator.comparing(Message::getCreatedAt).reversed());
        return messages;
    }

    private Map<String, Message> loadMessagesByMsgIdsPreferCache(List<String> msgIds) {
        if (msgIds == null || msgIds.isEmpty()) {
            return Map.of();
        }

        Map<String, Message> messageMap = new LinkedHashMap<>();
        List<String> missingMsgIds = new ArrayList<>();

        for (String msgId : msgIds) {
            Object cached = redisTemplate.opsForValue().get(buildMessageDetailKey(msgId));
            if (cached instanceof Message message) {
                messageMap.put(msgId, message);
            } else {
                missingMsgIds.add(msgId);
            }
        }

        if (!missingMsgIds.isEmpty()) {
            List<Message> dbMessages = messageMapper.selectList(new LambdaQueryWrapper<Message>()
                    .in(Message::getMsgId, missingMsgIds));
            for (Message message : dbMessages) {
                ensureCreatedAt(message);
                cacheRecentMessage(message);
                messageMap.put(message.getMsgId(), message);
            }
        }

        Map<String, Message> ordered = new LinkedHashMap<>();
        for (String msgId : msgIds) {
            Message message = messageMap.get(msgId);
            if (message != null) {
                ordered.put(msgId, message);
            }
        }
        return ordered;
    }

    private SliceResult<Message> queryOlderMessagesSlice(LambdaQueryWrapper<Message> baseQuery,
                                                         LocalDateTime oldestCachedTime,
                                                         int offset,
                                                         int limit) {
        if (limit <= 0) {
            return new SliceResult<>(List.of(), false);
        }
        LambdaQueryWrapper<Message> query = cloneQuery(baseQuery);
        if (oldestCachedTime != null) {
            query.lt(Message::getCreatedAt, oldestCachedTime);
        }
        query.orderByDesc(Message::getCreatedAt)
                .last("limit " + offset + "," + (limit + 1));
        List<Message> queried = messageMapper.selectList(query);
        boolean hasMore = queried.size() > limit;
        if (hasMore) {
            queried = new ArrayList<>(queried.subList(0, limit));
        }
        return new SliceResult<>(queried, hasMore);
    }

    private LambdaQueryWrapper<Message> buildPrivateHistoryQuery(Long userId, Long targetId, Long conversationId) {
        LambdaQueryWrapper<Message> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Message::getConversationId, conversationId)
                .and(wrapper -> wrapper
                .eq(Message::getFromUserId, userId).eq(Message::getToUserId, targetId)
                .or()
                .eq(Message::getFromUserId, targetId).eq(Message::getToUserId, userId)
        );
        return queryWrapper;
    }

    private LambdaQueryWrapper<Message> buildGroupHistoryQuery(Long groupId, Long conversationId) {
        LambdaQueryWrapper<Message> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Message::getConversationId, conversationId)
                .eq(Message::getGroupId, groupId);
        return queryWrapper;
    }

    private LambdaQueryWrapper<Message> cloneQuery(LambdaQueryWrapper<Message> source) {
        return source.clone();
    }

    private String buildPrivateRecentKey(Long userA, Long userB) {
        long min = Math.min(userA, userB);
        long max = Math.max(userA, userB);
        return RedisConstants.IM_RECENT_PRIVATE_PREFIX + min + ":" + max;
    }

    private String buildGroupRecentKey(Long groupId) {
        return RedisConstants.IM_RECENT_GROUP_PREFIX + groupId;
    }

    private String buildMessageDetailKey(String msgId) {
        return RedisConstants.IM_MESSAGE_DETAIL_PREFIX + msgId;
    }

    private long toEpochMilli(LocalDateTime time) {
        return time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private LocalDateTime toLocalDateTime(Long epochMilli) {
        return LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(epochMilli), ZoneId.systemDefault());
    }

    private ConversationSearchScope resolveConversationSearchScope(Long userId, Integer conversationType, Long targetId) {
        if (conversationType == null || targetId == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "会话类型和目标会话不能为空");
        }
        if (conversationType.equals(MessageInboxConstants.CONVERSATION_PRIVATE)) {
            Long conversationId = buildConversationId(buildConversationKey(userId, targetId));
            return new ConversationSearchScope(conversationId, null,
                    buildPrivateHistoryQuery(userId, targetId, conversationId));
        }
        if (conversationType.equals(MessageInboxConstants.CONVERSATION_GROUP)) {
            ensureGroupMember(userId, targetId);
            Long conversationId = buildConversationId(buildGroupConversationKey(targetId));
            return new ConversationSearchScope(conversationId, targetId,
                    buildGroupHistoryQuery(targetId, conversationId));
        }
        throw new BusinessException(ResultCode.PARAM_ERROR, "不支持的会话类型");
    }

    private List<Message> loadContextMessages(ConversationSearchScope scope, Message anchor, int limit, boolean beforeAnchor) {
        LambdaQueryWrapper<Message> query = new LambdaQueryWrapper<Message>()
                .eq(Message::getConversationId, scope.conversationId());
        if (scope.groupId() != null) {
            query.eq(Message::getGroupId, scope.groupId());
        }
        if (beforeAnchor) {
            query.and(wrapper -> wrapper
                    .lt(Message::getCreatedAt, anchor.getCreatedAt())
                    .or(nested -> nested
                            .eq(Message::getCreatedAt, anchor.getCreatedAt())
                            .lt(Message::getId, anchor.getId())));
        } else {
            query.and(wrapper -> wrapper
                    .gt(Message::getCreatedAt, anchor.getCreatedAt())
                    .or(nested -> nested
                            .eq(Message::getCreatedAt, anchor.getCreatedAt())
                            .gt(Message::getId, anchor.getId())));
        }
        if (beforeAnchor) {
            query.orderByDesc(Message::getCreatedAt).orderByDesc(Message::getId);
        } else {
            query.orderByAsc(Message::getCreatedAt).orderByAsc(Message::getId);
        }
        query.last("limit " + limit);
        return messageMapper.selectList(query);
    }

    private Message getAnchorMessage(ConversationSearchScope scope, String msgId) {
        LambdaQueryWrapper<Message> query = new LambdaQueryWrapper<Message>()
                .eq(Message::getMsgId, msgId)
                .eq(Message::getConversationId, scope.conversationId())
                .last("limit 1");
        if (scope.groupId() != null) {
            query.eq(Message::getGroupId, scope.groupId());
        }
        Message anchor = messageMapper.selectOne(query);
        if (anchor != null) {
            cacheMessageDetail(anchor);
        }
        return anchor;
    }

    private record ConversationSearchScope(Long conversationId, Long groupId,
                                           LambdaQueryWrapper<Message> baseQuery) {
    }

    private record SliceResult<T>(List<T> records, boolean hasMore) {
    }

    private void ensureCreatedAt(Message message) {
        if (message.getCreatedAt() == null) {
            message.setCreatedAt(LocalDateTime.now());
        }
    }

    private Long allocateGroupSeq(Long groupId) {
        String seqKey = RedisConstants.IM_GROUP_SEQ_PREFIX + groupId;
        Boolean hasKey = redisTemplate.hasKey(seqKey);
        if (Boolean.FALSE.equals(hasKey)) {
            String lockKey = seqKey + ":init";
            Boolean locked = redisTemplate.opsForValue().setIfAbsent(lockKey, "1", 3, TimeUnit.SECONDS);
            if (Boolean.TRUE.equals(locked)) {
                try {
                    Long latestSeq = getLatestGroupSeqFromDb(groupId);
                    redisTemplate.opsForValue().setIfAbsent(seqKey, latestSeq == null ? 0L : latestSeq);
                } finally {
                    redisTemplate.delete(lockKey);
                }
            }
        }
        Long nextSeq = redisTemplate.opsForValue().increment(seqKey);
        return nextSeq == null ? 1L : nextSeq;
    }

    private Long allocateConversationSeq(Long userA, Long userB) {
        long minUid = Math.min(userA, userB);
        long maxUid = Math.max(userA, userB);
        String seqKey = RedisConstants.IM_CONV_SEQ_PREFIX + "single:" + minUid + ":" + maxUid;
        Long nextSeq = redisTemplate.opsForValue().increment(seqKey);
        if (nextSeq == null) {
            return 1L;
        }
        return nextSeq;
    }

    private Long getLatestGroupSeqFromDb(Long groupId) {
        Long conversationId = buildConversationId(buildGroupConversationKey(groupId));
        Message latest = messageMapper.selectOne(new LambdaQueryWrapper<Message>()
                .eq(Message::getConversationId, conversationId)
                .eq(Message::getGroupId, groupId)
                .isNotNull(Message::getGroupSeq)
                .orderByDesc(Message::getGroupSeq)
                .last("limit 1"));
        return latest == null || latest.getGroupSeq() == null ? 0L : latest.getGroupSeq();
    }

    private Message getLatestGroupMessage(Long groupId) {
        Long conversationId = buildConversationId(buildGroupConversationKey(groupId));
        String cacheKey = buildGroupRecentKey(groupId);
        warmRecentCacheIfNeeded(cacheKey, buildGroupHistoryQuery(groupId, conversationId));
        List<Message> recentMessages = getRecentMessagesFromCache(cacheKey, 0, 0);
        if (!recentMessages.isEmpty()) {
            return recentMessages.get(0);
        }
        return messageMapper.selectOne(new LambdaQueryWrapper<Message>()
                .eq(Message::getConversationId, conversationId)
                .eq(Message::getGroupId, groupId)
                .orderByDesc(Message::getGroupSeq)
                .last("limit 1"));
    }

    private void markGroupConversationAsRead(Long userId, Long groupId) {
        if (groupId == null) {
            return;
        }
        Message latest = getLatestGroupMessage(groupId);
        long latestSeq = latest == null || latest.getGroupSeq() == null ? 0L : latest.getGroupSeq();
        markGroupMemberReadSeq(userId, groupId, latestSeq);
    }

    public void markGroupMemberReadSeq(Long userId, Long groupId, Long readSeq) {
        if (userId == null || groupId == null || readSeq == null) {
            return;
        }
        GroupMember membership = groupMemberMapper.selectOne(new LambdaQueryWrapper<GroupMember>()
                .eq(GroupMember::getGroupId, groupId)
                .eq(GroupMember::getUserId, userId)
                .last("limit 1"));
        if (membership == null) {
            return;
        }
        long current = membership.getLastReadSeq() == null ? 0L : membership.getLastReadSeq();
        if (readSeq <= current) {
            return;
        }
        membership.setLastReadSeq(readSeq);
        groupMemberMapper.updateById(membership);
    }

    public long getCurrentGroupSeq(Long groupId) {
        Message latest = getLatestGroupMessage(groupId);
        return latest == null || latest.getGroupSeq() == null ? 0L : latest.getGroupSeq();
    }
}
