package com.epsilon.welink.im.consumer;

import com.epsilon.welink.im.event.GroupMessageIngressEvent;
import com.epsilon.welink.im.event.PrivateMessageIngressEvent;
import com.epsilon.welink.im.service.IMService;
import com.epsilon.welink.message.dto.MessageRequest;
import com.epsilon.welink.message.entity.Message;
import com.epsilon.welink.message.service.MessageService;
import com.epsilon.welink.metrics.MetricsCollector;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Slf4j
@Component
public class MessageConsumer {

    private final IMService imService;
    private final MessageService messageService;
    private final MetricsCollector metricsCollector;

    public MessageConsumer(IMService imService, MessageService messageService, MetricsCollector metricsCollector) {
        this.imService = imService;
        this.messageService = messageService;
        this.metricsCollector = metricsCollector;
    }

    @KafkaListener(topics = "im-private-ingress", groupId = "${welink.im.ingress.consumer-group:welink-im-private-ingress}",
            concurrency = "${welink.kafka.private-ingress-concurrency:8}")
    public void consumePrivateIngress(PrivateMessageIngressEvent event) {
        String traceId = event.getTraceId() != null ? event.getTraceId() : UUID.randomUUID().toString().substring(0, 8);
        MDC.put("traceId", traceId);
        try {
            MessageRequest request = new MessageRequest();
            request.setMsgId(event.getMsgId());
            request.setClientMsgId(event.getClientMsgId());
            request.setToUserId(event.getToUserId());
            request.setMsgType(event.getMsgType());
            request.setContent(event.getContent());

            Long targetUserId = event.getToUserId();
            boolean needsOutbox = imService.isUserOnlineOnOtherInstance(targetUserId);
            Message message = messageService.buildAndEnqueuePrivateMessage(
                    event.getFromUserId(),
                    request,
                    targetUserId,
                    needsOutbox
            );
            if (message == null) {
                log.warn("[traceId={}] message deduped or failed sender={}", traceId, event.getFromUserId());
                return;
            }
            log.info("[traceId={}] msgId={} sender={} -> [SAVE:ingress] target={}",
                    traceId, message.getMsgId(), event.getFromUserId(), targetUserId);
            metricsCollector.recordMessageSent(true, event.getReceiveTime());
            imService.dispatchPersistedPrivateMessage(traceId, message, targetUserId, needsOutbox);
        } finally {
            MDC.remove("traceId");
        }
    }

    @KafkaListener(topics = "im-group-ingress", groupId = "${welink.im.group-ingress.consumer-group:welink-im-group-ingress}",
            concurrency = "${welink.kafka.group-ingress-concurrency:16}")
    public void consumeGroupIngress(GroupMessageIngressEvent event) {
        String traceId = event.getTraceId() != null ? event.getTraceId() : UUID.randomUUID().toString().substring(0, 8);
        MDC.put("traceId", traceId);
        try {
            MessageRequest request = new MessageRequest();
            request.setMsgId(event.getMsgId());
            request.setClientMsgId(event.getClientMsgId());
            request.setGroupId(event.getGroupId());
            request.setMsgType(event.getMsgType());
            request.setContent(event.getContent());

            Message message = imService.processGroupIngressBatch(
                    traceId, event.getFromUserId(), request, event.getReceiveTime());
            if (message != null) {
                log.info("[traceId={}] msgId={} sender={} -> [SAVE:group-ingress] group={}",
                        traceId, message.getMsgId(), event.getFromUserId(), event.getGroupId());
            }
        } finally {
            MDC.remove("traceId");
        }
    }

    @KafkaListener(topics = "im-private-message", groupId = "${welink.im.consumer-group}",
            concurrency = "${welink.kafka.private-concurrency:4}")
    public void consumePrivateMessage(Message message) {
        String traceId = UUID.randomUUID().toString().substring(0, 8);
        MDC.put("traceId", traceId);
        try {
            Long targetUserId = message.getToUserId();
            log.info("[msgId={}] [CONSUME:private] toUserId={}", message.getMsgId(), targetUserId);
            if (!imService.pushMessage(targetUserId, message)) {
                log.info("[msgId={}] target user {} not local, skip", message.getMsgId(), targetUserId);
            } else {
                metricsCollector.recordMessageDeliverKafka();
            }
        } finally {
            MDC.remove("traceId");
        }
    }

    @KafkaListener(topics = "im-group-message", groupId = "${welink.im.consumer-group}",
            concurrency = "${welink.kafka.group-concurrency:4}")
    public void consumeGroupMessage(Message message,
                                    @Header(value = KafkaHeaders.RECEIVED_KEY, required = false) String targetUserIdStr) {
        String traceId = UUID.randomUUID().toString().substring(0, 8);
        MDC.put("traceId", traceId);
        try {
            if (targetUserIdStr == null || targetUserIdStr.isBlank()) {
                log.warn("[msgId={}] [CONSUME:group] missing Kafka key (targetUserId), skip", message.getMsgId());
                return;
            }
            Long targetUserId;
            try {
                targetUserId = Long.valueOf(targetUserIdStr);
            } catch (NumberFormatException e) {
                log.warn("[msgId={}] [CONSUME:group] invalid Kafka key '{}', skip", message.getMsgId(), targetUserIdStr);
                return;
            }
            log.info("[msgId={}] [CONSUME:group] groupId={} toUserId={}", message.getMsgId(), message.getGroupId(), targetUserId);
            if (!imService.pushMessage(targetUserId, message)) {
                log.info("[msgId={}] target user {} not local, skip", message.getMsgId(), targetUserId);
            } else {
                metricsCollector.recordMessageDeliverKafka();
            }
        } finally {
            MDC.remove("traceId");
        }
    }
}
