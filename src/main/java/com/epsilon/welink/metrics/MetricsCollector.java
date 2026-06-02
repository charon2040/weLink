package com.epsilon.welink.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

@Component
public class MetricsCollector {

    private final MeterRegistry registry;
    private final AtomicLong onlineConnections = new AtomicLong(0);

    private final Counter connectionsCreated;
    private final Counter connectionsClosed;
    private final Counter connectionsClosedTimeout;
    private final Counter connectionsClosedError;
    private final Counter messageSentPrivate;
    private final Counter messageSentGroup;
    private final Counter messageSentLargeGroup;
    private final Counter messageDeliverLocalSuccess;
    private final Counter messageDeliverKafkaSuccess;
    private final Counter rateLimitHitsUser;
    private final Counter rateLimitHitsGroup;
    private final Timer messageSendLatency;

    public MetricsCollector(MeterRegistry registry) {
        this.registry = registry;

        Gauge.builder("welink.online.connections", onlineConnections, AtomicLong::get)
                .description("Current number of active WebSocket connections")
                .baseUnit("connections")
                .register(registry);

        this.connectionsCreated = Counter.builder("welink.connections.created")
                .description("Total connections created")
                .baseUnit("connections")
                .register(registry);

        this.connectionsClosed = Counter.builder("welink.connections.closed")
                .description("Total connections closed")
                .baseUnit("connections")
                .register(registry);

        this.connectionsClosedTimeout = Counter.builder("welink.connections.closed")
                .tag("reason", "timeout")
                .description("Connections closed due to heartbeat timeout")
                .baseUnit("connections")
                .register(registry);

        this.connectionsClosedError = Counter.builder("welink.connections.closed")
                .tag("reason", "error")
                .description("Connections closed due to error")
                .baseUnit("connections")
                .register(registry);

        this.messageSentPrivate = Counter.builder("welink.message.sent")
                .tag("type", "private")
                .description("Private messages sent")
                .baseUnit("messages")
                .register(registry);

        this.messageSentGroup = Counter.builder("welink.message.sent")
                .tag("type", "group")
                .description("Group messages sent")
                .baseUnit("messages")
                .register(registry);

        this.messageSentLargeGroup = Counter.builder("welink.message.sent")
                .tag("type", "large_group")
                .description("Large group messages sent (read-diffusion)")
                .baseUnit("messages")
                .register(registry);

        this.messageDeliverLocalSuccess = Counter.builder("welink.message.deliver.success")
                .tag("method", "local")
                .description("Messages delivered via local WebSocket push")
                .baseUnit("messages")
                .register(registry);

        this.messageDeliverKafkaSuccess = Counter.builder("welink.message.deliver.success")
                .tag("method", "kafka")
                .description("Messages delivered via Kafka cross-instance")
                .baseUnit("messages")
                .register(registry);

        this.rateLimitHitsUser = Counter.builder("welink.ratelimit.hits")
                .tag("type", "user")
                .description("User rate limit hits")
                .baseUnit("rejections")
                .register(registry);

        this.rateLimitHitsGroup = Counter.builder("welink.ratelimit.hits")
                .tag("type", "group")
                .description("Group rate limit hits")
                .baseUnit("rejections")
                .register(registry);

        this.messageSendLatency = Timer.builder("welink.message.send.latency")
                .description("Message send latency from receive to persist ACK")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
    }

    public void recordConnectionCreated() {
        onlineConnections.incrementAndGet();
        connectionsCreated.increment();
    }

    public void recordConnectionClosed(boolean timeout, boolean error) {
        onlineConnections.decrementAndGet();
        connectionsClosed.increment();
        if (timeout) {
            connectionsClosedTimeout.increment();
        }
        if (error) {
            connectionsClosedError.increment();
        }
    }

    public void recordMessageSent(boolean isPrivate, long receiveTime) {
        if (isPrivate) {
            messageSentPrivate.increment();
        } else {
            messageSentGroup.increment();
        }
        messageSendLatency.record(System.currentTimeMillis() - receiveTime, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    public void recordLargeGroupMessageSent() {
        messageSentLargeGroup.increment();
    }

    public void recordMessageDeliverLocal() {
        messageDeliverLocalSuccess.increment();
    }

    public void recordMessageDeliverKafka() {
        messageDeliverKafkaSuccess.increment();
    }

    public void recordRateLimitHitUser() {
        rateLimitHitsUser.increment();
    }

    public void recordRateLimitHitGroup() {
        rateLimitHitsGroup.increment();
    }

    public Timer.Sample startTimer() {
        return Timer.start(registry);
    }
}
