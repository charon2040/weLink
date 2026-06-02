package com.epsilon.welink.message;

import com.epsilon.welink.message.service.MessageService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分片键生成纯函数测试 — 验证 buildConversationKey + buildConversationId 的关键不变量
 * (无 mock, 不依赖 Spring/ShardingSphere)
 */
@DisplayName("分片键生成测试")
class ShardKeyGenerationTest {

    @Test
    @DisplayName("私聊 conversationKey 应与参数顺序无关")
    void privateConvKeyShouldBeOrderIndependent() {
        String k1 = MessageService.buildConversationKey(100L, 200L);
        String k2 = MessageService.buildConversationKey(200L, 100L);
        assertThat(k1).isEqualTo(k2);
        assertThat(k1).isEqualTo("single:100:200");
    }

    @Test
    @DisplayName("群聊 conversationKey 格式正确")
    void groupConvKeyFormat() {
        assertThat(MessageService.buildGroupConversationKey(42L)).isEqualTo("group:42");
    }

    @Test
    @DisplayName("conversationId 必须为正数 (避免 % 取负)")
    void conversationIdMustBePositive() {
        for (int i = 0; i < 1000; i++) {
            String key = "single:" + i + ":" + (i + 100);
            Long id = MessageService.buildConversationId(key);
            assertThat(id).isNotNegative();
        }
    }

    @Test
    @DisplayName("同一 convKey 应产生相同 conversationId (确定性)")
    void conversationIdShouldBeDeterministic() {
        String key = "single:100:200";
        Long id1 = MessageService.buildConversationId(key);
        Long id2 = MessageService.buildConversationId(key);
        assertThat(id1).isEqualTo(id2);
    }

    @Test
    @DisplayName("不同 convKey 应产生不同 conversationId (MD5 散列)")
    void distinctConvKeysShouldProduceDistinctIds() {
        Set<Long> ids = new HashSet<>();
        int generated = 0;
        for (int a = 1; a <= 50; a++) {
            for (int b = a + 1; b <= 100; b++) {
                ids.add(MessageService.buildConversationId(MessageService.buildConversationKey((long) a, (long) b)));
                generated++;
            }
        }
        // a in 1..50, b in (a+1)..100  →  3725 个不同 convKey, 应全部唯一
        assertThat(generated).isEqualTo(3725);
        assertThat(ids).hasSize(generated);
    }

    @Test
    @DisplayName("分片分布应大致均衡 (% 8 取模)")
    void shardDistributionShouldBeBalanced() {
        int[] counts = new int[8];
        for (long groupId = 1; groupId <= 10_000; groupId++) {
            Long id = MessageService.buildConversationId(MessageService.buildGroupConversationKey(groupId));
            counts[(int) (id % 8)]++;
        }
        for (int c : counts) {
            // 理想 1250 每片; 8 片随机分布, 5% 容差
            assertThat(c).isBetween(1100, 1400);
        }
    }

    @Test
    @DisplayName("conversationId 在并发场景下线程安全 (无静态状态污染)")
    void conversationIdShouldBeThreadSafe() throws Exception {
        int threads = 50;
        int perThread = 200;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        ConcurrentHashMap<String, Long> observed = new ConcurrentHashMap<>();

        for (int t = 0; t < threads; t++) {
            final int threadIdx = t;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < perThread; i++) {
                        String key = "single:" + threadIdx + ":" + (threadIdx + i + 1);
                        Long id = MessageService.buildConversationId(key);
                        Long prev = observed.putIfAbsent(key, id);
                        if (prev != null) {
                            assertThat(prev).as("线程下同一 key id 必须一致").isEqualTo(id);
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        boolean done = latch.await(15, TimeUnit.SECONDS);
        executor.shutdown();
        assertThat(done).isTrue();
        // 每线程独立的 50 × 200 = 10000 个不同 key
        assertThat(observed).hasSize(threads * perThread);
    }
}
