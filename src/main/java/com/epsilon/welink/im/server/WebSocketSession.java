package com.epsilon.welink.im.server;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class WebSocketSession {

    @Getter
    private final Channel channel;
    @Getter
    private final Long userId;
    @Getter
    private final String deviceId;
    private volatile boolean closed;
    private final AtomicInteger requestCount = new AtomicInteger(0);
    private volatile long windowStart = System.currentTimeMillis();
    private static final int MAX_REQUESTS_PER_SECOND = 500;

    public WebSocketSession(Channel channel, Long userId, String deviceId) {
        this.channel = channel;
        this.userId = userId;
        this.deviceId = deviceId;
    }

    public boolean writeAsync(String message) {
        if (closed || !channel.isActive()) {
            return false;
        }
        if (!channel.isWritable()) {
            log.warn("Slow consumer, outbound buffer over high watermark: user={} device={}", userId, deviceId);
            return false;
        }
        channel.writeAndFlush(new TextWebSocketFrame(message));
        return true;
    }

    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            if (channel.isActive()) {
                channel.close();
            }
        } catch (Exception ignored) {
        }
    }

    public boolean isActive() {
        return !closed && channel.isActive();
    }

    public boolean allowRequest() {
        long now = System.currentTimeMillis();
        if (now - windowStart > 1000) {
            requestCount.set(0);
            windowStart = now;
        }
        return requestCount.incrementAndGet() <= MAX_REQUESTS_PER_SECOND;
    }
}
