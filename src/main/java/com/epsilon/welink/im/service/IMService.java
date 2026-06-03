package com.epsilon.welink.im.service;

import com.epsilon.welink.common.DegradationManager;
import com.epsilon.welink.common.constant.RedisConstants;
import com.epsilon.welink.common.util.JwtUtil;
import com.epsilon.welink.im.event.GroupMessageIngressEvent;
import com.epsilon.welink.im.event.PrivateMessageIngressEvent;
import com.epsilon.welink.message.constant.MessageInboxConstants;
import com.epsilon.welink.message.dto.MessageRequest;
import com.epsilon.welink.message.entity.Message;
import com.epsilon.welink.message.service.MessageService;
import com.epsilon.welink.metrics.MetricsCollector;
import com.epsilon.welink.relation.entity.GroupMember;
import com.epsilon.welink.relation.service.RelationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.Channel;
import com.epsilon.welink.im.server.WebSocketSession;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import org.springframework.data.redis.core.RedisCallback;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
public class IMService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final JwtUtil jwtUtil;
    private final MessageService messageService;
    private final RelationService relationService;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final MetricsCollector metricsCollector;
    private final DegradationManager degradationManager;
    private final Executor imAsyncExecutor;
    private final java.util.concurrent.ForkJoinPool groupPushExecutor =
            new java.util.concurrent.ForkJoinPool(32);

    @Value("${welink.im.send-rate-limit-per-second:3000}")
    private int sendRateLimitPerSecond;

    @Value("${welink.im.group-rate-limit-per-second:10}")
    private int groupSendRateLimitPerSecond;

    @Value("${welink.im.max-message-size:65536}")
    private int maxMessageSize;

    @Value("${welink.im.large-group-threshold:5000}")
    private int largeGroupThreshold;

    @Value("${welink.im.medium-group-threshold:500}")
    private int mediumGroupThreshold;

    @Value("${welink.instance.id:instance-1}")
    private String instanceId;

    private final ConcurrentHashMap<Long, ConcurrentHashMap<String, WebSocketSession>> onlineUsers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> channelUserMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> channelDeviceMap = new ConcurrentHashMap<>();
    private static final String DEFAULT_DEVICE_ID = "default";
    private static final String TOPIC_GROUP_INGRESS = "im-group-ingress";
    private static final String TOPIC_PRIVATE_INGRESS = "im-private-ingress";
    private static final String TOPIC_LARGE_GROUP = "im-large-group-message";

    public IMService(RedisTemplate<String, Object> redisTemplate,
                     JwtUtil jwtUtil,
                     MessageService messageService,
                     RelationService relationService,
                     KafkaTemplate<String, Object> kafkaTemplate,
                     MetricsCollector metricsCollector,
                     DegradationManager degradationManager,
                     @Qualifier("imAsyncExecutor") Executor imAsyncExecutor) {
        this.redisTemplate = redisTemplate;
        this.jwtUtil = jwtUtil;
        this.messageService = messageService;
        this.relationService = relationService;
        this.kafkaTemplate = kafkaTemplate;
        this.metricsCollector = metricsCollector;
        this.degradationManager = degradationManager;
        this.imAsyncExecutor = imAsyncExecutor;
        this.objectMapper = new ObjectMapper();
    }

    public void handleMessage(ChannelHandlerContext ctx, String message) {
        try {
            JsonNode jsonNode = objectMapper.readTree(message);
            String type = jsonNode.get("type").asText();

            switch (type) {
                case "auth" -> handleAuth(ctx, jsonNode);
                case "message" -> handleSendMessage(ctx, jsonNode);
                case "heartbeat" -> handleHeartbeat(ctx);
                case "ack" -> handleAck(ctx, jsonNode);
                case "recall" -> handleRecall(ctx, jsonNode);
                default -> sendError(ctx, "Unknown message type");
            }
        } catch (Exception e) {
            log.error("Failed to handle message", e);
            sendError(ctx, "Invalid message format");
        }
    }

    public String handleInternalMessage(String message) {
        String traceId = java.util.UUID.randomUUID().toString().substring(0, 8);
        MDC.put("traceId", traceId);
        try {
            JsonNode jsonNode = objectMapper.readTree(message);
            String type = jsonNode.get("type").asText();
            if ("message".equals(type)) {
                return handleSendMessageInternal(traceId, jsonNode);
            }
            return "{\"type\":\"error\",\"message\":\"unknown type\"}";
        } catch (Exception e) {
            return "{\"type\":\"error\",\"message\":\"invalid format\"}";
        } finally {
            MDC.remove("traceId");
        }
    }

    private String handleSendMessageInternal(String traceId, JsonNode jsonNode) {
        Long userId = jsonNode.get("userId").asLong();
        if (!allowSend(userId)) {
            metricsCollector.recordRateLimitHitUser();
            return "{\"type\":\"error\",\"message\":\"rate limit exceeded\"}";
        }
        if (!jsonNode.has("toUserId")) {
            return "{\"type\":\"error\",\"message\":\"missing toUserId\"}";
        }
        Long targetUserId = jsonNode.get("toUserId").asLong();
        String msgId = jsonNode.has("msgId") ? jsonNode.get("msgId").asText() : java.util.UUID.randomUUID().toString();
        String content = jsonNode.get("content").asText();
        if (content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > maxMessageSize) {
            return "{\"type\":\"error\",\"message\":\"message too large\"}";
        }

        MessageRequest request = new MessageRequest();
        request.setMsgId(msgId);
        request.setToUserId(targetUserId);
        request.setMsgType(jsonNode.has("msgType") ? jsonNode.get("msgType").asInt() : 1);
        request.setContent(content);
        if (jsonNode.has("clientMsgId")) request.setClientMsgId(jsonNode.get("clientMsgId").asText());

        boolean needsOutbox = isUserOnlineOnOtherInstance(targetUserId);
        Message saved = messageService.sendPrivateMessageTransactional(userId, request, targetUserId, needsOutbox);
        log.info("[traceId={}] msgId={} sender={} -> [SAVE:internal]", traceId, saved.getMsgId(), userId);

        schedulePrivateDelivery(traceId, saved, targetUserId, needsOutbox);
        metricsCollector.recordMessageSent(true, System.currentTimeMillis());
        return "{\"type\":\"message\",\"status\":\"success\",\"data\":\"" + saved.getMsgId() + "\"}";
    }

    public void pushToUserInternal(Long userId, String messageJson) {
        pushToAllSessionsRaw(userId, messageJson);
    }

    private void handleAuth(ChannelHandlerContext ctx, JsonNode jsonNode) {
        String token = jsonNode.get("token").asText();
        if (!jwtUtil.validateToken(token)) {
            sendError(ctx, "Invalid token");
            ctx.close();
            return;
        }

        Long userId = jwtUtil.getUserId(token);
        String deviceId = jsonNode.has("deviceId") ? jsonNode.get("deviceId").asText() : DEFAULT_DEVICE_ID;

        String normalizedDeviceId = normalizeDeviceId(deviceId);
        ConcurrentHashMap<String, WebSocketSession> deviceSessions = onlineUsers.computeIfAbsent(userId,
                k -> new ConcurrentHashMap<>());

        WebSocketSession oldSession = deviceSessions.get(normalizedDeviceId);
        if (oldSession != null && oldSession.isActive()) {
            channelUserMap.remove(oldSession.getChannel().id().asShortText());
            channelDeviceMap.remove(oldSession.getChannel().id().asShortText());
            oldSession.close();
        }

        WebSocketSession session = new WebSocketSession(ctx.channel(), userId, normalizedDeviceId);
        deviceSessions.put(normalizedDeviceId, session);
        channelUserMap.put(ctx.channel().id().asShortText(), userId);
        channelDeviceMap.put(ctx.channel().id().asShortText(), normalizedDeviceId);

        redisTemplate.executePipelined((RedisCallback<Void>) connection -> {
            byte[] onlineKey = redisTemplate.getStringSerializer().serialize(RedisConstants.USER_ONLINE_PREFIX + userId);
            byte[] routeKey = redisTemplate.getStringSerializer()
                    .serialize(RedisConstants.IM_ROUTE_PREFIX + userId + ":" + normalizedDeviceId);
            byte[] routeUserKey = redisTemplate.getStringSerializer()
                    .serialize(RedisConstants.IM_ROUTE_USER_PREFIX + userId);
            byte[] valBytes = redisTemplate.getStringSerializer().serialize("online");
            byte[] routeValBytes = redisTemplate.getStringSerializer()
                    .serialize(instanceId + ":" + ctx.channel().id().asShortText());
            byte[] instanceBytes = redisTemplate.getStringSerializer().serialize(instanceId);

            connection.stringCommands().set(onlineKey, valBytes);
            connection.keyCommands().expire(onlineKey, RedisConstants.ONLINE_TTL_SECONDS);
            connection.stringCommands().set(routeKey, routeValBytes);
            connection.keyCommands().expire(routeKey, RedisConstants.ROUTE_TTL_SECONDS);
            connection.stringCommands().set(routeUserKey, instanceBytes);
            connection.keyCommands().expire(routeUserKey, RedisConstants.ROUTE_TTL_SECONDS);
            return null;
        });

        metricsCollector.recordConnectionCreated();
        sendSuccess(ctx, "auth", "Authentication successful");
        log.info("User {} device {} authenticated and online", userId, normalizedDeviceId);
    }

    private String normalizeDeviceId(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            return DEFAULT_DEVICE_ID;
        }
        String lower = deviceId.toLowerCase().trim();
        if (lower.contains("web") || lower.contains("browser")) return "web";
        if (lower.contains("ios") || lower.contains("iphone") || lower.contains("ipad")) return "ios";
        if (lower.contains("android")) return "android";
        if (lower.contains("desktop") || lower.contains("pc") || lower.contains("mac") || lower.contains("windows"))
            return "desktop";
        return lower.replaceAll("[^a-z0-9]", "");
    }

    private void handleSendMessage(ChannelHandlerContext ctx, JsonNode jsonNode) {
        Long userId = getUserIdByChannel(ctx.channel());
        if (userId == null) {
            sendError(ctx, "Not authenticated");
            return;
        }
        if (!allowSend(userId)) {
            metricsCollector.recordRateLimitHitUser();
            sendError(ctx, "Rate limit exceeded");
            return;
        }

        long receiveTime = System.currentTimeMillis();
        String traceId = java.util.UUID.randomUUID().toString().substring(0, 8);
        MDC.put("traceId", traceId);
        try {
            MessageRequest request = new MessageRequest();
            if (jsonNode.has("toUserId")) request.setToUserId(jsonNode.get("toUserId").asLong());
            if (jsonNode.has("groupId")) request.setGroupId(jsonNode.get("groupId").asLong());
            if (jsonNode.has("msgId")) request.setMsgId(jsonNode.get("msgId").asText());
            if (jsonNode.has("clientMsgId")) request.setClientMsgId(jsonNode.get("clientMsgId").asText());
            request.setMsgType(jsonNode.has("msgType") ? jsonNode.get("msgType").asInt() : 1);
            String content = jsonNode.get("content").asText();
            if (content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > maxMessageSize) {
                sendError(ctx, "Message too large, max " + maxMessageSize / 1024 + "KB");
                return;
            }
            request.setContent(content);

            if (request.getToUserId() != null) {
                publishPrivateIngress(ctx, traceId, userId, request, receiveTime);
                return;
            } else if (request.getGroupId() != null) {
                if (!allowGroupSend(request.getGroupId(), userId)) {
                    metricsCollector.recordRateLimitHitGroup();
                    sendError(ctx, "Group rate limit exceeded");
                    return;
                }
                publishGroupIngress(ctx, traceId, userId, request, receiveTime);
                return;
            } else {
                sendError(ctx, "Invalid message parameters");
                return;
            }
        } finally {
            MDC.remove("traceId");
        }
    }

    private void publishPrivateIngress(ChannelHandlerContext ctx, String traceId, Long senderId,
                                       MessageRequest request, long receiveTime) {
        String msgId = StringUtils.hasText(request.getMsgId()) ? request.getMsgId().trim() : java.util.UUID.randomUUID().toString();
        request.setMsgId(msgId);

        PrivateMessageIngressEvent event = new PrivateMessageIngressEvent();
        event.setTraceId(traceId);
        event.setMsgId(msgId);
        event.setClientMsgId(request.getClientMsgId());
        event.setFromUserId(senderId);
        event.setToUserId(request.getToUserId());
        event.setMsgType(request.getMsgType() != null ? request.getMsgType() : 1);
        event.setContent(request.getContent());
        event.setReceiveTime(receiveTime);
        event.setSourceInstanceId(instanceId);

        String conversationKey = MessageService.buildConversationKey(senderId, request.getToUserId());
        kafkaTemplate.send(TOPIC_PRIVATE_INGRESS, conversationKey, event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("[traceId={}] Failed to publish private ingress sender={} target={}",
                                traceId, senderId, request.getToUserId(), ex);
                        sendError(ctx, "Message ingress unavailable");
                        return;
                    }
                    log.info("[traceId={}] msgId={} sender={} -> [ACCEPT:ingress] target={}",
                            traceId, msgId, senderId, request.getToUserId());
                    sendSuccess(ctx, "message", msgId);
                });
    }

    private void publishGroupIngress(ChannelHandlerContext ctx, String traceId, Long senderId,
                                     MessageRequest request, long receiveTime) {
        String msgId = StringUtils.hasText(request.getMsgId()) ? request.getMsgId().trim() : java.util.UUID.randomUUID().toString();
        request.setMsgId(msgId);

        GroupMessageIngressEvent event = new GroupMessageIngressEvent();
        event.setTraceId(traceId);
        event.setMsgId(msgId);
        event.setClientMsgId(request.getClientMsgId());
        event.setFromUserId(senderId);
        event.setGroupId(request.getGroupId());
        event.setMsgType(request.getMsgType() != null ? request.getMsgType() : 1);
        event.setContent(request.getContent());
        event.setReceiveTime(receiveTime);
        event.setSourceInstanceId(instanceId);

        String conversationKey = MessageService.buildGroupConversationKey(request.getGroupId());
        kafkaTemplate.send(TOPIC_GROUP_INGRESS, conversationKey, event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("[traceId={}] Failed to publish group ingress sender={} group={}",
                                traceId, senderId, request.getGroupId(), ex);
                        sendError(ctx, "Message ingress unavailable");
                        return;
                    }
                    log.info("[traceId={}] msgId={} sender={} -> [ACCEPT:group-ingress] group={}",
                            traceId, msgId, senderId, request.getGroupId());
                    sendSuccess(ctx, "message", msgId);
                });
    }

    public Message processGroupIngressBatch(String traceId, Long senderId, MessageRequest request, long receiveTime) {
        Long groupId = request.getGroupId();
        List<GroupMember> members = relationService.getGroupMembersForDispatch(groupId);
        int memberCount = members.size();

        if (memberCount > mediumGroupThreshold) {
            return handleLargeGroupMessageBatch(traceId, senderId, request, groupId, members, receiveTime);
        } else {
            return handleWriteDiffusionMessageBatch(traceId, senderId, request, groupId, members, receiveTime);
        }
    }

    private Message handleWriteDiffusionMessageBatch(String traceId, Long senderId, MessageRequest request,
                                                      Long groupId, List<GroupMember> members, long receiveTime) {
        RouteBuckets routeBuckets = classifyWriteDiffusionRecipients(members, senderId);
        List<Long> otherInstanceIds = routeBuckets.otherInstanceIds();
        List<Long> offlineIds = routeBuckets.offlineIds();

        Message message = messageService.buildAndEnqueueGroupMessage(
                senderId, request, groupId, otherInstanceIds, offlineIds);
        if (message == null) return null;

        log.info("[traceId={}] msgId={} sender={} -> [SAVE:group-ingress-batch] group={} members={} otherInstance={} offline={}",
                traceId, message.getMsgId(), senderId, groupId, members.size(), otherInstanceIds.size(), offlineIds.size());

        pushToGroupOnlineMembers(message, members, senderId);
        metricsCollector.recordMessageSent(false, receiveTime);
        return message;
    }

    private Message handleLargeGroupMessageBatch(String traceId, Long senderId, MessageRequest request,
                                                  Long groupId, List<GroupMember> members, long receiveTime) {
        Message message = messageService.buildAndEnqueueGroupMessage(
                senderId, request, groupId, null, null);
        if (message == null) return null;

        log.info("[traceId={}] msgId={} sender={} -> [SAVE:large-group-batch] group={} members={}",
                traceId, message.getMsgId(), senderId, groupId, members.size());

        int localPushed = 0;
        List<Long> nonLocalUserIds = new ArrayList<>();
        for (GroupMember member : members) {
            if (member.getUserId().equals(senderId)) continue;
            if (hasLocalSession(member.getUserId())) {
                groupPushExecutor.submit(() -> pushToAllSessions(member.getUserId(), message));
                localPushed++;
            } else {
                nonLocalUserIds.add(member.getUserId());
            }
        }
        boolean anyOnOtherInstance = hasAnyUserOnlineOnOtherInstance(nonLocalUserIds);

        if (anyOnOtherInstance) {
            try {
                String convKey = MessageService.buildGroupConversationKey(groupId);
                kafkaTemplate.send(TOPIC_LARGE_GROUP, convKey, message);
            } catch (Exception e) {
                log.error("[traceId={}] Failed to publish large group message to Kafka", traceId, e);
            }
        }
        log.info("[traceId={}] msgId={} large-group fanout: localPushed={} crossInstance={}",
                traceId, message.getMsgId(), localPushed, anyOnOtherInstance);

        metricsCollector.recordLargeGroupMessageSent();
        return message;
    }

    private void pushToGroupOnlineMembers(Message saved, List<GroupMember> members, Long senderId) {
        for (GroupMember member : members) {
            if (member.getUserId().equals(senderId)) continue;
            groupPushExecutor.submit(() -> pushToAllSessions(member.getUserId(), saved));
        }
    }

    private RouteBuckets classifyWriteDiffusionRecipients(List<GroupMember> members, Long senderId) {
        List<Long> candidateIds = new ArrayList<>();
        for (GroupMember member : members) {
            Long userId = member.getUserId();
            if (userId.equals(senderId) || hasLocalSession(userId)) {
                continue;
            }
            candidateIds.add(userId);
        }
        if (candidateIds.isEmpty()) {
            return new RouteBuckets(new ArrayList<>(), new ArrayList<>());
        }

        List<String> routeKeys = candidateIds.stream()
                .map(userId -> RedisConstants.IM_ROUTE_USER_PREFIX + userId)
                .collect(Collectors.toList());
        List<Object> routes = null;
        try {
            routes = redisTemplate.opsForValue().multiGet(routeKeys);
        } catch (Exception e) {
            log.warn("Failed to batch read aggregate routes for group fanout", e);
        }

        List<Long> otherInstanceIds = new ArrayList<>();
        List<Long> offlineIds = new ArrayList<>();
        List<Long> unresolvedIds = new ArrayList<>();
        for (int i = 0; i < candidateIds.size(); i++) {
            Long userId = candidateIds.get(i);
            Object route = routes != null && i < routes.size() ? routes.get(i) : null;
            if (route instanceof String routeInstance && StringUtils.hasText(routeInstance)) {
                if (!instanceId.equals(routeInstance)) {
                    otherInstanceIds.add(userId);
                } else {
                    offlineIds.add(userId);
                }
            } else {
                unresolvedIds.add(userId);
            }
        }

        for (Long userId : unresolvedIds) {
            offlineIds.add(userId);
        }
        return new RouteBuckets(otherInstanceIds, offlineIds);
    }

    private boolean hasAnyUserOnlineOnOtherInstance(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return false;
        }
        List<String> routeKeys = userIds.stream()
                .map(userId -> RedisConstants.IM_ROUTE_USER_PREFIX + userId)
                .collect(Collectors.toList());
        try {
            List<Object> routes = redisTemplate.opsForValue().multiGet(routeKeys);
            if (routes == null || routes.isEmpty()) {
                return false;
            }
            for (Object route : routes) {
                if (route instanceof String routeInstance
                        && StringUtils.hasText(routeInstance)
                        && !instanceId.equals(routeInstance)) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            log.warn("Failed to batch read aggregate routes for large-group fanout", e);
            return false;
        }
    }

    private void handleHeartbeat(ChannelHandlerContext ctx) {
        Long userId = getUserIdByChannel(ctx.channel());
        if (userId != null) {
            String deviceId = channelDeviceMap.getOrDefault(ctx.channel().id().asShortText(), DEFAULT_DEVICE_ID);
            try {
                String routeKey = RedisConstants.IM_ROUTE_PREFIX + userId + ":" + deviceId;
                redisTemplate.executePipelined((RedisCallback<Void>) connection -> {
                    connection.keyCommands().expire(
                            redisTemplate.getStringSerializer().serialize(RedisConstants.USER_ONLINE_PREFIX + userId),
                            RedisConstants.ONLINE_TTL_SECONDS
                    );
                    connection.keyCommands().expire(
                            redisTemplate.getStringSerializer().serialize(routeKey),
                            RedisConstants.ROUTE_TTL_SECONDS
                    );
                    connection.keyCommands().expire(
                            redisTemplate.getStringSerializer().serialize(RedisConstants.IM_ROUTE_USER_PREFIX + userId),
                            RedisConstants.ROUTE_TTL_SECONDS
                    );
                    return null;
                });
            } catch (Exception e) {
                log.warn("Heartbeat Redis pipeline failed for user={}, will retry next heartbeat", userId, e);
            }
            sendSuccess(ctx, "heartbeat", "pong");
        }
    }

    private void handleAck(ChannelHandlerContext ctx, JsonNode jsonNode) {
        if (!degradationManager.isReadReceiptEnabled()) {
            return;
        }
        Long userId = getUserIdByChannel(ctx.channel());
        if (userId == null) {
            sendError(ctx, "Not authenticated");
            return;
        }
        if (!jsonNode.has("msgId")) {
            sendError(ctx, "msgId is required");
            return;
        }

        String msgId = jsonNode.get("msgId").asText();
        messageService.markAsRead(msgId, userId);
        log.info("Message {} marked as read by user {}", msgId, userId);
    }

    private void handleRecall(ChannelHandlerContext ctx, JsonNode jsonNode) {
        Long userId = getUserIdByChannel(ctx.channel());
        if (userId == null) {
            sendError(ctx, "Not authenticated");
            return;
        }
        if (!jsonNode.has("msgId")) {
            sendError(ctx, "msgId is required");
            return;
        }
        String msgId = jsonNode.get("msgId").asText();
        Message message = messageService.getMessageByMsgId(msgId);
        if (message == null || !message.getFromUserId().equals(userId)) {
            sendError(ctx, "Message not found or not yours");
            return;
        }
        long elapsed = System.currentTimeMillis() - message.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        if (elapsed > 120_000) {
            sendError(ctx, "Recall window expired (2 minutes)");
            return;
        }
        message.setStatus(2);
        try {
            messageService.recallMessage(message);
        } catch (Exception e) {
            sendError(ctx, "Recall failed");
            return;
        }
        sendSuccess(ctx, "recall", msgId);
        recallToAllSessions(message);
    }

    private void recallToAllSessions(Message message) {
        String recallPayload = "{\"type\":\"recall\",\"msgId\":\"" + message.getMsgId() + "\"}";
        // 1) 本实例推
        if (message.getToUserId() != null) {
            pushToAllSessionsRaw(message.getToUserId(), recallPayload);
            pushToAllSessionsRaw(message.getFromUserId(), recallPayload);  // 同步给发送者其他设备
        } else if (message.getGroupId() != null) {
            List<GroupMember> members = relationService.getGroupMembersForDispatch(message.getGroupId());
            for (GroupMember member : members) {
                pushToAllSessionsRaw(member.getUserId(), recallPayload);
            }
        }

        // 2) 广播到其他实例 (避免 receiver 在别的实例上时收不到撤回)
        try {
            Map<String, Object> event = new HashMap<>();
            event.put("msgId", message.getMsgId());
            event.put("fromUserId", message.getFromUserId());
            event.put("toUserId", message.getToUserId());
            event.put("groupId", message.getGroupId());
            event.put("sourceInstanceId", instanceId);
            kafkaTemplate.send("im-recall", message.getMsgId(), event);
        } catch (Exception e) {
            log.error("Failed to broadcast recall to Kafka for msgId={}", message.getMsgId(), e);
        }
    }

    @KafkaListener(topics = "im-recall", groupId = "${welink.im.consumer-group}",
            concurrency = "${welink.kafka.recall-concurrency:2}")
    public void consumeRecall(String eventJson) {
        String traceId = java.util.UUID.randomUUID().toString().substring(0, 8);
        MDC.put("traceId", traceId);
        try {
            JsonNode json = objectMapper.readTree(eventJson);
            String sourceInstanceId = json.has("sourceInstanceId") ? json.get("sourceInstanceId").asText() : "";
            // 自己实例发出的 — 本地已经推过, 跳过避免双推
            if (instanceId.equals(sourceInstanceId)) {
                return;
            }
            String msgId = json.get("msgId").asText();
            String recallPayload = "{\"type\":\"recall\",\"msgId\":\"" + msgId + "\"}";

            Long toUserId = json.has("toUserId") && !json.get("toUserId").isNull() ? json.get("toUserId").asLong() : null;
            Long groupId = json.has("groupId") && !json.get("groupId").isNull() ? json.get("groupId").asLong() : null;
            Long fromUserId = json.has("fromUserId") && !json.get("fromUserId").isNull() ? json.get("fromUserId").asLong() : null;

            if (toUserId != null) {
                pushToAllSessionsRaw(toUserId, recallPayload);
                // 发送者其他设备如果在本实例也要同步
                if (fromUserId != null) pushToAllSessionsRaw(fromUserId, recallPayload);
            } else if (groupId != null) {
                // 只推本实例上有 session 的成员, 避免无谓遍历所有群成员
                List<GroupMember> members = relationService.getGroupMembersForDispatch(groupId);
                for (GroupMember m : members) {
                    if (hasLocalSession(m.getUserId())) {
                        pushToAllSessionsRaw(m.getUserId(), recallPayload);
                    }
                }
            }
            log.info("[traceId={}] [CONSUME:recall] msgId={} from instance {}", traceId, msgId, sourceInstanceId);
        } catch (Exception e) {
            log.error("Failed to consume recall event", e);
        } finally {
            MDC.remove("traceId");
        }
    }

    public void handleDisconnect(ChannelHandlerContext ctx) {
        String channelId = ctx.channel().id().asShortText();
        Long userId = getUserIdByChannel(ctx.channel());
        String deviceId = channelDeviceMap.remove(channelId);
        channelUserMap.remove(channelId);

        if (userId != null && deviceId != null) {
            ConcurrentHashMap<String, WebSocketSession> deviceSessions = onlineUsers.get(userId);
            if (deviceSessions != null) {
                WebSocketSession session = deviceSessions.remove(deviceId);
                if (session != null) {
                    session.close();
                }
                if (deviceSessions.isEmpty()) {
                    onlineUsers.remove(userId);
                    redisTemplate.delete(RedisConstants.USER_ONLINE_PREFIX + userId);
                }
            }
            redisTemplate.delete(RedisConstants.IM_ROUTE_PREFIX + userId + ":" + deviceId);
            Object aggregateRoute = redisTemplate.opsForValue().get(RedisConstants.IM_ROUTE_USER_PREFIX + userId);
            if (instanceId.equals(aggregateRoute)) {
                redisTemplate.delete(RedisConstants.IM_ROUTE_USER_PREFIX + userId);
            }

            boolean allOffline = !hasLocalSession(userId);
            boolean wasTimeout = false;
            metricsCollector.recordConnectionClosed(wasTimeout, false);
            log.info("User {} device {} disconnected (allOffline={})", userId, deviceId, allOffline);
        }
    }

    public boolean pushMessage(Long userId, Message message) {
        return pushToAllSessions(userId, message);
    }

    private boolean pushToAllSessions(Long userId, Message message) {
        ConcurrentHashMap<String, WebSocketSession> sessions = onlineUsers.get(userId);
        if (sessions == null || sessions.isEmpty()) {
            return false;
        }

        boolean anyPushed = false;
        String json = buildMessageJson(message);
        for (WebSocketSession session : sessions.values()) {
            if (session.isActive()) {
                if (session.writeAsync(json)) {
                    anyPushed = true;
                }
            }
        }
        return anyPushed;
    }

    private void pushToAllSessionsRaw(Long userId, String json) {
        ConcurrentHashMap<String, WebSocketSession> sessions = onlineUsers.get(userId);
        if (sessions == null || sessions.isEmpty()) {
            return;
        }
        for (WebSocketSession session : sessions.values()) {
            if (session.isActive()) {
                session.writeAsync(json);
            }
        }
    }

    private boolean hasLocalSession(Long userId) {
        ConcurrentHashMap<String, WebSocketSession> sessions = onlineUsers.get(userId);
        return sessions != null && sessions.values().stream().anyMatch(WebSocketSession::isActive);
    }

    private boolean allowSend(Long userId) {
        String key = RedisConstants.IM_RATE_LIMIT_PREFIX + userId + ":" + (System.currentTimeMillis() / 1000);
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redisTemplate.expire(key, 2, TimeUnit.SECONDS);
        }
        return count == null || count <= sendRateLimitPerSecond;
    }

    private boolean allowGroupSend(Long groupId, Long userId) {
        String key = "im:rate:group:" + groupId + ":" + userId + ":" + (System.currentTimeMillis() / 1000);
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redisTemplate.expire(key, 2, TimeUnit.SECONDS);
        }
        return count == null || count <= groupSendRateLimitPerSecond;
    }

    public Long getUserIdByChannelId(String channelId) {
        return channelUserMap.get(channelId);
    }

    public WebSocketSession getSession(Long userId) {
        ConcurrentHashMap<String, WebSocketSession> sessions = onlineUsers.get(userId);
        if (sessions == null || sessions.isEmpty()) {
            return null;
        }
        return sessions.values().iterator().next();
    }

    public WebSocketSession getSessionByChannelId(String channelId) {
        Long userId = channelUserMap.get(channelId);
        String deviceId = channelDeviceMap.get(channelId);
        if (userId == null || deviceId == null) return null;
        ConcurrentHashMap<String, WebSocketSession> sessions = onlineUsers.get(userId);
        if (sessions == null) return null;
        return sessions.get(deviceId);
    }

    public Set<String> getDeviceIds(Long userId) {
        ConcurrentHashMap<String, WebSocketSession> sessions = onlineUsers.get(userId);
        if (sessions == null) return Set.of();
        return sessions.keySet();
    }

    public int getOnlineCount() {
        return onlineUsers.size();
    }

    private Long getUserIdByChannel(Channel channel) {
        return channelUserMap.get(channel.id().asShortText());
    }

    private String buildMessageJson(Message message) {
        try {
            Map<String, Object> map = new HashMap<>();
            map.put("type", "message");
            map.put("msgId", message.getMsgId());
            map.put("fromUserId", message.getFromUserId());
            map.put("toUserId", message.getToUserId());
            map.put("groupId", message.getGroupId());
            map.put("groupSeq", message.getGroupSeq());
            map.put("conversationSeq", message.getConversationSeq());
            map.put("msgType", message.getMsgType());
            map.put("content", message.getContent());
            map.put("createdAt", message.getCreatedAt() != null ? message.getCreatedAt().toString() : null);
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            log.error("Failed to build message json", e);
            return "";
        }
    }

    private void sendSuccess(ChannelHandlerContext ctx, String type, String data) {
        try {
            Map<String, Object> map = new HashMap<>();
            map.put("type", type);
            map.put("status", "success");
            map.put("data", data);
            String payload = objectMapper.writeValueAsString(map);
            ctx.writeAndFlush(new TextWebSocketFrame(payload)).addListener(future -> {
                if (!future.isSuccess()) {
                    log.warn("Failed to send success frame type={} channel={}", type, ctx.channel().id().asShortText(), future.cause());
                }
            });
        } catch (Exception e) {
            log.error("Failed to send success response", e);
        }
    }

    private void sendError(ChannelHandlerContext ctx, String message) {
        try {
            Map<String, Object> map = new HashMap<>();
            map.put("type", "error");
            map.put("message", message);
            ctx.writeAndFlush(new TextWebSocketFrame(objectMapper.writeValueAsString(map)));
        } catch (Exception e) {
            log.error("Failed to send error response", e);
        }
    }

    private void broadcastFriendStatus(Long userId, boolean isOnline) {
        if (!degradationManager.isPresenceBroadcastEnabled()) {
            return;
        }
        List<Long> friendIds = relationService.getFriendIds(userId);
        if (friendIds.isEmpty()) {
            return;
        }

        String payload = buildSystemStatusJson(userId, isOnline);
        for (Long friendId : friendIds) {
            pushToAllSessionsRaw(friendId, payload);
        }

        try {
            Map<String, Object> event = new HashMap<>();
            event.put("userId", userId);
            event.put("isOnline", isOnline);
            event.put("instanceId", instanceId);
            kafkaTemplate.send("im-presence", String.valueOf(userId), objectMapper.writeValueAsString(event));
        } catch (Exception e) {
            log.error("Failed to broadcast presence to Kafka for user {}", userId, e);
        }
    }

    private void schedulePresenceBroadcast(Long userId, boolean isOnline) {
        if (!degradationManager.isPresenceBroadcastEnabled()) {
            return;
        }
        runAsyncSafely("presence:" + userId + ":" + isOnline, () -> broadcastFriendStatus(userId, isOnline));
    }

    private void schedulePrivateDelivery(String traceId, Message message, Long targetUserId, boolean needsOutbox) {
        boolean pushed = pushToAllSessions(targetUserId, message);
        if (pushed) {
            metricsCollector.recordMessageDeliverLocal();
            log.info("[traceId={}] msgId={} -> [PUSH:local] target={}", traceId, message.getMsgId(), targetUserId);
        } else if (needsOutbox) {
            metricsCollector.recordMessageDeliverKafka();
            log.info("[traceId={}] msgId={} -> [ROUTE:outbox] target={}", traceId, message.getMsgId(), targetUserId);
        } else {
            log.info("[traceId={}] msgId={} -> [PUSH:offline] target={} (no outbox needed)", traceId, message.getMsgId(), targetUserId);
        }
    }

    public void dispatchPersistedPrivateMessage(String traceId, Message message, Long targetUserId, boolean needsOutbox) {
        runAsyncSafely("privateDelivery", () -> schedulePrivateDelivery(traceId, message, targetUserId, needsOutbox));
    }

    private void runAsyncSafely(String taskName, Runnable task) {
        CompletableFuture.runAsync(() -> {
            try {
                task.run();
            } catch (Exception e) {
                log.error("Async task failed: {}", taskName, e);
            }
        }, imAsyncExecutor);
    }

    @KafkaListener(topics = "im-presence", groupId = "${welink.im.consumer-group}",
            concurrency = "${welink.kafka.presence-concurrency:2}")
    public void consumePresence(String eventJson) {
        String traceId = java.util.UUID.randomUUID().toString().substring(0, 8);
        MDC.put("traceId", traceId);
        try {
            JsonNode json = objectMapper.readTree(eventJson);
            Long userId = json.get("userId").asLong();
            boolean isOnline = json.get("isOnline").asBoolean();
            String sourceInstanceId = json.get("instanceId").asText();

            if (instanceId.equals(sourceInstanceId)) {
                return;
            }

            List<Long> friendIds = relationService.getFriendIds(userId);
            String payload = buildSystemStatusJson(userId, isOnline);
            for (Long friendId : friendIds) {
                pushToAllSessionsRaw(friendId, payload);
            }
        } catch (Exception e) {
            log.error("Failed to consume presence event", e);
        } finally {
            MDC.remove("traceId");
        }
    }

    private String buildSystemStatusJson(Long userId, boolean isOnline) {
        try {
            Map<String, Object> map = new HashMap<>();
            map.put("type", "system");
            map.put("action", isOnline ? "online" : "offline");
            map.put("userId", userId);
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            log.error("Failed to build system status json", e);
            return "";
        }
    }

    @KafkaListener(topics = "im-large-group-message", groupId = "${welink.im.consumer-group}",
            concurrency = "${welink.kafka.large-group-concurrency:3}")
    public void consumeLargeGroupMessage(com.epsilon.welink.message.entity.Message message) {
        String traceId = java.util.UUID.randomUUID().toString().substring(0, 8);
        MDC.put("traceId", traceId);
        try {
            log.info("[msgId={}] [CONSUME:large-group] groupId={}", message.getMsgId(), message.getGroupId());
            if (message.getGroupId() == null) {
                return;
            }
            List<GroupMember> members = relationService.getGroupMembersForDispatch(message.getGroupId());
            int pushed = 0;
            for (GroupMember member : members) {
                Long userId = member.getUserId();
                if (userId.equals(message.getFromUserId())) continue;
                if (!hasLocalSession(userId)) continue;
                if (pushToAllSessions(userId, message)) {
                    pushed++;
                }
            }
            log.info("[msgId={}] [CONSUME:large-group] localPushed={}/{}", message.getMsgId(), pushed, members.size());
            if (pushed > 0) {
                metricsCollector.recordMessageDeliverKafka();
            }
        } finally {
            MDC.remove("traceId");
        }
    }

    private record RouteBuckets(List<Long> otherInstanceIds, List<Long> offlineIds) {
    }

    public boolean isUserOnlineOnOtherInstance(Long userId) {
        try {
            Object aggregateRoute = redisTemplate.opsForValue().get(RedisConstants.IM_ROUTE_USER_PREFIX + userId);
            if (aggregateRoute instanceof String routeInstance && StringUtils.hasText(routeInstance)) {
                return !instanceId.equals(routeInstance);
            }
        } catch (Exception e) {
            log.warn("Failed to read aggregate route for userId={}", userId, e);
        }
        return false;
    }
}
