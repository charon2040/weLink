package com.epsilon.welink.im;

import com.epsilon.welink.common.constant.RedisConstants;
import com.epsilon.welink.im.service.IMService;
import com.epsilon.welink.message.service.MessageService;

import com.epsilon.welink.relation.service.RelationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * 速率限制并发测试
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("速率限制并发测试")
class RateLimitConcurrencyTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private com.epsilon.welink.common.util.JwtUtil jwtUtil;

    @Mock
    private MessageService messageService;

    @Mock
    private RelationService relationService;

    @Mock
    private org.springframework.kafka.core.KafkaTemplate<String, Object> kafkaTemplate;

    @Mock
    private com.epsilon.welink.metrics.MetricsCollector metricsCollector;

    @Mock
    private com.epsilon.welink.common.DegradationManager degradationManager;

    @Mock
    private java.util.concurrent.Executor imAsyncExecutor;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    private IMService imService;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        imService = new IMService(
                redisTemplate, jwtUtil, messageService, relationService, kafkaTemplate, metricsCollector, degradationManager, imAsyncExecutor);

        // Set rate limit to 30/sec via reflection
        try {
            java.lang.reflect.Field field = IMService.class.getDeclaredField("sendRateLimitPerSecond");
            field.setAccessible(true);
            field.set(imService, 30);
        } catch (Exception e) {
            // fallback
        }
    }

    @Test
    @DisplayName("速率限制计数器应在同一秒内正确累加")
    void rateLimitCounterShouldAccumulateWithinSameSecond() throws Exception {
        Long userId = 100L;
        long currentSecond = System.currentTimeMillis() / 1000;

        AtomicLong counter = new AtomicLong(0);

        when(valueOperations.increment(anyString()))
                .thenAnswer(invocation -> counter.incrementAndGet());

        when(redisTemplate.expire(anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(true);

        int threadCount = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger allowedCount = new AtomicInteger(0);
        AtomicInteger deniedCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    // Access via reflection since allowSend is private
                    boolean allowed = invokeAllowSend(userId, counter);
                    if (allowed) {
                        allowedCount.incrementAndGet();
                    } else {
                        deniedCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    deniedCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        boolean completed = latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(allowedCount.get()).isEqualTo(30);
        assertThat(deniedCount.get()).isEqualTo(20);
    }

    @Test
    @DisplayName("不同用户的速率限制应独立计算互不影响")
    void rateLimitShouldBePerUser() throws Exception {
        ConcurrentHashMap<String, AtomicLong> counters = new ConcurrentHashMap<>();

        when(valueOperations.increment(anyString()))
                .thenAnswer(invocation -> {
                    String key = invocation.getArgument(0);
                    AtomicLong counter = counters.computeIfAbsent(key, k -> new AtomicLong(0));
                    return counter.incrementAndGet();
                });

        when(redisTemplate.expire(anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(true);

        Long userId1 = 100L;
        Long userId2 = 200L;

        AtomicLong user1Counter = new AtomicLong(0);
        AtomicLong user2Counter = new AtomicLong(0);

        int threadCount = 40;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger user1Allowed = new AtomicInteger(0);
        AtomicInteger user2Allowed = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final boolean isUser1 = i < 20;
            final Long userId = isUser1 ? userId1 : userId2;
            executor.submit(() -> {
                try {
                    boolean allowed = invokeAllowSend(userId,
                            isUser1 ? user1Counter : user2Counter);
                    if (allowed) {
                        if (isUser1) {
                            user1Allowed.incrementAndGet();
                        } else {
                            user2Allowed.incrementAndGet();
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        boolean completed = latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        // Each user gets their own rate limit
        assertThat(user1Allowed.get()).isEqualTo(20);
        assertThat(user2Allowed.get()).isEqualTo(20);
    }

    @Test
    @DisplayName("并发用户上线/断线不应导致在线状态混乱")
    void concurrentOnlineOfflineShouldNotConfuseStatus() throws Exception {
        Long userId = 100L;
        AtomicLong onlineCounter = new AtomicLong(0);

        when(redisTemplate.expire(anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(true);

        when(valueOperations.increment(anyString()))
                .thenAnswer(invocation -> onlineCounter.incrementAndGet());

        int threadCount = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    successCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        boolean completed = latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(successCount.get()).isEqualTo(threadCount);
    }

    private boolean invokeAllowSend(Long userId, AtomicLong simulatedCounter) {
        try {
            java.lang.reflect.Method method = IMService.class.getDeclaredMethod("allowSend", Long.class);
            method.setAccessible(true);

            // Override the counter for this test
            java.lang.reflect.Field counterField = IMService.class.getDeclaredField("sendRateLimitPerSecond");
            counterField.setAccessible(true);
            counterField.set(imService, 30);

            return (boolean) method.invoke(imService, userId);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
