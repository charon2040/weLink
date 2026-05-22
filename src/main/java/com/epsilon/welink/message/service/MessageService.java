package com.epsilon.welink.message.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.epsilon.welink.common.constant.RedisConstants;
import com.epsilon.welink.message.constant.MessageInboxConstants;
import com.epsilon.welink.message.dto.ConversationSummaryDTO;
import com.epsilon.welink.message.dto.MessageRequest;
import com.epsilon.welink.message.entity.Message;
import com.epsilon.welink.message.entity.MessageInbox;
import com.epsilon.welink.message.mapper.MessageInboxMapper;
import com.epsilon.welink.message.mapper.MessageMapper;
import com.epsilon.welink.relation.entity.GroupMember;
import com.epsilon.welink.relation.mapper.GroupMemberMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class MessageService {

    private final MessageMapper messageMapper;
    private final MessageInboxMapper messageInboxMapper;
    private final GroupMemberMapper groupMemberMapper;
    private final RedisTemplate<String, Object> redisTemplate;

    public MessageService(MessageMapper messageMapper,
                          MessageInboxMapper messageInboxMapper,
                          GroupMemberMapper groupMemberMapper,
                          RedisTemplate<String, Object> redisTemplate) {
        this.messageMapper = messageMapper;
        this.messageInboxMapper = messageInboxMapper;
        this.groupMemberMapper = groupMemberMapper;
        this.redisTemplate = redisTemplate;
    }

    public Message savePrivateMessage(Long fromUserId, MessageRequest request) {
        return saveMessageCore(fromUserId, request, true);
    }

    public Message saveGroupMessage(Long fromUserId, MessageRequest request) {
        return saveMessageCore(fromUserId, request, false);
    }

    private Message saveMessageCore(Long fromUserId, MessageRequest request, boolean isPrivate) {
        String msgId = resolveMsgId(request.getMsgId());
        if (!isPrivate) {
            Message existing = getMessageByMsgId(msgId);
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
        message.setMsgType(request.getMsgType() != null ? request.getMsgType() : 1);
        message.setContent(request.getContent());
        message.setStatus(MessageInboxConstants.STATUS_SENT);

        try {
            messageMapper.insert(message);
            ensureCreatedAt(message);
            cacheRecentMessage(message);
            if (!isPrivate) {
                markGroupMemberReadSeq(fromUserId, request.getGroupId(), message.getGroupSeq());
            }
            return message;
        } catch (DuplicateKeyException e) {
            Message duplicate = getMessageByMsgId(msgId);
            if (duplicate != null) {
                cacheRecentMessage(duplicate);
            }
            return duplicate;
        }
    }

    private String resolveMsgId(String msgId) {
        return StringUtils.hasText(msgId) ? msgId.trim() : UUID.randomUUID().toString();
    }

    public void createInboxRecord(String msgId, Long receiverId, Integer conversationType) {
        MessageInbox inbox = new MessageInbox();
        inbox.setMsgId(msgId);
        inbox.setReceiverId(receiverId);
        inbox.setConversationType(conversationType);
        inbox.setStatus(MessageInboxConstants.STATUS_SENT);
        try {
            messageInboxMapper.insert(inbox);
        } catch (DuplicateKeyException ignored) {
        }
    }

    public void createInboxRecords(String msgId, List<Long> receiverIds, Integer conversationType) {
        if (receiverIds == null || receiverIds.isEmpty()) {
            return;
        }

        List<MessageInbox> inboxList = receiverIds.stream()
                .map(receiverId -> {
                    MessageInbox inbox = new MessageInbox();
                    inbox.setMsgId(msgId);
                    inbox.setReceiverId(receiverId);
                    inbox.setConversationType(conversationType);
                    inbox.setStatus(MessageInboxConstants.STATUS_SENT);
                    return inbox;
                })
                .collect(Collectors.toList());

        try {
            messageInboxMapper.insertBatch(inboxList);
        } catch (DuplicateKeyException ignored) {
        }
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
    }

    public Page<Message> getPrivateHistory(Long userId, Long targetId, Integer pageNum, Integer pageSize) {
        String cacheKey = buildPrivateRecentKey(userId, targetId);
        LambdaQueryWrapper<Message> baseQuery = buildPrivateHistoryQuery(userId, targetId);
        return getHistoryWithRecentCache(cacheKey, baseQuery, pageNum, pageSize);
    }

    public Page<Message> getGroupHistory(Long groupId, Integer pageNum, Integer pageSize) {
        String cacheKey = buildGroupRecentKey(groupId);
        LambdaQueryWrapper<Message> baseQuery = buildGroupHistoryQuery(groupId);
        return getHistoryWithRecentCache(cacheKey, baseQuery, pageNum, pageSize);
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
                inbox.setStatus(MessageInboxConstants.STATUS_DELIVERED);
                messageInboxMapper.updateById(inbox);
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

        LambdaQueryWrapper<MessageInbox> inboxQuery = new LambdaQueryWrapper<>();
        inboxQuery.eq(MessageInbox::getReceiverId, receiverId)
                .eq(MessageInbox::getConversationType, conversationType)
                .lt(MessageInbox::getStatus, MessageInboxConstants.STATUS_READ)
                .orderByAsc(MessageInbox::getCreatedAt);
        List<MessageInbox> inboxList = messageInboxMapper.selectList(inboxQuery);
        if (inboxList.isEmpty()) {
            return;
        }

        List<String> msgIds = inboxList.stream().map(MessageInbox::getMsgId).distinct().toList();
        Map<String, Message> messageMap = loadMessagesByMsgIdsPreferCache(msgIds);

        for (MessageInbox inbox : inboxList) {
            Message message = messageMap.get(inbox.getMsgId());
            if (!belongsToConversation(receiverId, conversationType, targetId, message)) {
                continue;
            }
            inbox.setStatus(MessageInboxConstants.STATUS_READ);
            messageInboxMapper.updateById(inbox);
        }
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

        inbox.setStatus(MessageInboxConstants.STATUS_READ);
        messageInboxMapper.updateById(inbox);
    }

    public Message getMessageByMsgId(String msgId) {
        return messageMapper.selectOne(
                new LambdaQueryWrapper<Message>().eq(Message::getMsgId, msgId)
        );
    }

    private Page<Message> getHistoryWithRecentCache(String cacheKey,
                                                    LambdaQueryWrapper<Message> baseQuery,
                                                    Integer pageNum,
                                                    Integer pageSize) {
        int safePageNum = pageNum != null && pageNum > 0 ? pageNum : 1;
        int safePageSize = pageSize != null && pageSize > 0 ? pageSize : 50;
        warmRecentCacheIfNeeded(cacheKey, baseQuery);

        long cacheCount = getRecentCacheCount(cacheKey);
        List<Message> recentMessages = getRecentMessagesFromCache(cacheKey, 0, cacheCount > 0 ? cacheCount - 1 : -1);
        LocalDateTime oldestCachedTime = recentMessages.isEmpty() ? null : recentMessages.get(recentMessages.size() - 1).getCreatedAt();

        int start = (safePageNum - 1) * safePageSize;
        int end = start + safePageSize - 1;
        List<Message> records = new ArrayList<>();

        if (start < cacheCount) {
            records.addAll(getRecentMessagesFromCache(cacheKey, start, Math.min(end, (int) cacheCount - 1)));
            int remaining = safePageSize - records.size();
            if (remaining > 0) {
                records.addAll(queryOlderMessages(baseQuery, oldestCachedTime, 0, remaining));
            }
        } else {
            int olderOffset = (int) (start - cacheCount);
            records.addAll(queryOlderMessages(baseQuery, oldestCachedTime, olderOffset, safePageSize));
        }

        long olderCount = countOlderMessages(baseQuery, oldestCachedTime);
        Page<Message> page = new Page<>(safePageNum, safePageSize);
        page.setRecords(records);
        page.setTotal(cacheCount + olderCount);
        return page;
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

    private List<Message> queryOlderMessages(LambdaQueryWrapper<Message> baseQuery,
                                             LocalDateTime oldestCachedTime,
                                             int offset,
                                             int limit) {
        if (limit <= 0) {
            return List.of();
        }
        LambdaQueryWrapper<Message> query = cloneQuery(baseQuery);
        if (oldestCachedTime != null) {
            query.lt(Message::getCreatedAt, oldestCachedTime);
        }
        query.orderByDesc(Message::getCreatedAt)
                .last("limit " + offset + "," + limit);
        return messageMapper.selectList(query);
    }

    private long countOlderMessages(LambdaQueryWrapper<Message> baseQuery, LocalDateTime oldestCachedTime) {
        LambdaQueryWrapper<Message> query = cloneQuery(baseQuery);
        if (oldestCachedTime != null) {
            query.lt(Message::getCreatedAt, oldestCachedTime);
        }
        Long count = messageMapper.selectCount(query);
        return count == null ? 0L : count;
    }

    private LambdaQueryWrapper<Message> buildPrivateHistoryQuery(Long userId, Long targetId) {
        LambdaQueryWrapper<Message> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.and(wrapper -> wrapper
                .eq(Message::getFromUserId, userId).eq(Message::getToUserId, targetId)
                .or()
                .eq(Message::getFromUserId, targetId).eq(Message::getToUserId, userId)
        );
        return queryWrapper;
    }

    private LambdaQueryWrapper<Message> buildGroupHistoryQuery(Long groupId) {
        LambdaQueryWrapper<Message> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Message::getGroupId, groupId);
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

    private void ensureCreatedAt(Message message) {
        if (message.getCreatedAt() == null) {
            message.setCreatedAt(LocalDateTime.now());
        }
    }

    private Long allocateGroupSeq(Long groupId) {
        String seqKey = RedisConstants.IM_GROUP_SEQ_PREFIX + groupId;
        Boolean hasKey = redisTemplate.hasKey(seqKey);
        if (Boolean.FALSE.equals(hasKey)) {
            Long latestSeq = getLatestGroupSeqFromDb(groupId);
            redisTemplate.opsForValue().setIfAbsent(seqKey, latestSeq == null ? 0L : latestSeq);
        }
        Long nextSeq = redisTemplate.opsForValue().increment(seqKey);
        return nextSeq == null ? 1L : nextSeq;
    }

    private Long getLatestGroupSeqFromDb(Long groupId) {
        Message latest = messageMapper.selectOne(new LambdaQueryWrapper<Message>()
                .eq(Message::getGroupId, groupId)
                .isNotNull(Message::getGroupSeq)
                .orderByDesc(Message::getGroupSeq)
                .last("limit 1"));
        return latest == null || latest.getGroupSeq() == null ? 0L : latest.getGroupSeq();
    }

    private Message getLatestGroupMessage(Long groupId) {
        String cacheKey = buildGroupRecentKey(groupId);
        warmRecentCacheIfNeeded(cacheKey, buildGroupHistoryQuery(groupId));
        List<Message> recentMessages = getRecentMessagesFromCache(cacheKey, 0, 0);
        if (!recentMessages.isEmpty()) {
            return recentMessages.get(0);
        }
        return messageMapper.selectOne(new LambdaQueryWrapper<Message>()
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
