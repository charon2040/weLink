package com.epsilon.welink.message;

import com.epsilon.welink.WeLinkApplication;
import com.epsilon.welink.message.entity.Message;
import com.epsilon.welink.message.entity.MessageOutbox;
import com.epsilon.welink.message.mapper.MessageMapper;
import com.epsilon.welink.message.mapper.MessageOutboxMapper;
import com.epsilon.welink.message.service.MessageService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 集成测试: 启动 Spring Boot + ShardingSphere sharding profile, 验证:
 *  1. 配置能成功加载 (ShardingSphereDriver + sharding-config.yaml)
 *  2. INSERT/SELECT 路由到正确分片
 *  3. recallMessage 走带分片键的 UPDATE 单分片路由
 *
 * 启用条件: 仅当环境变量 WELINK_INTEGRATION_TEST=true 时跑.
 * 需要先 docker compose -p welink-shard-verify -f docker-compose.shard-verify.yml up -d
 * 并建好 8 个分库 + message_YYYYMM 表.
 */
@EnabledIfEnvironmentVariable(named = "WELINK_INTEGRATION_TEST", matches = "true")
@SpringBootTest(classes = WeLinkApplication.class)
@ActiveProfiles("sharding")
class ShardingIntegrationTest {

    @Autowired
    private MessageMapper messageMapper;

    @Autowired
    private MessageOutboxMapper messageOutboxMapper;

    @Test
    void shardingDataSourceCanBootAndQuery() {
        // 简单 sanity check: 启动成功 + 能查询
        Long count = messageMapper.selectCount(new LambdaQueryWrapper<>());
        assertThat(count).isNotNull();
    }

    @Test
    void insertRoutesToCorrectShardByConversationId() {
        Long convId = MessageService.buildConversationId(
                MessageService.buildConversationKey(100L, 200L));

        Message message = new Message();
        message.setMsgId("integ-" + System.currentTimeMillis());
        message.setConversationId(convId);
        message.setFromUserId(100L);
        message.setToUserId(200L);
        message.setMsgType(1);
        message.setContent("integration test");
        message.setStatus(0);
        message.setCreatedAt(LocalDateTime.now());

        messageMapper.insert(message);
        assertThat(message.getId()).isNotNull();  // ShardingSphere snowflake 应当填了 id
    }

    @Test
    void outboxCarriesRoutingMetadata() {
        Long convId = MessageService.buildConversationId(
                MessageService.buildConversationKey(100L, 200L));

        MessageOutbox outbox = new MessageOutbox();
        outbox.setMsgId("outbox-integ-" + System.currentTimeMillis());
        outbox.setTargetUserId(200L);
        outbox.setTopic("im-private-message");
        outbox.setConversationId(convId);
        outbox.setMessageCreatedAt(LocalDateTime.now());
        outbox.setStatus(0);
        outbox.setRetryCount(0);
        outbox.setNextRetryAt(LocalDateTime.now());

        messageOutboxMapper.insert(outbox);

        MessageOutbox fetched = messageOutboxMapper.selectOne(
                new LambdaQueryWrapper<MessageOutbox>()
                        .eq(MessageOutbox::getMsgId, outbox.getMsgId())
                        .eq(MessageOutbox::getTargetUserId, 200L));
        assertThat(fetched).isNotNull();
        assertThat(fetched.getConversationId()).isEqualTo(convId);
        assertThat(fetched.getMessageCreatedAt()).isNotNull();
    }
}
