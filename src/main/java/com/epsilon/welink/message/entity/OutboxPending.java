package com.epsilon.welink.message.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Outbox 待发布索引表(非分片, 在 welink 库).
 * <p>
 * 与分片的 message_outbox 配合: createOutboxRecord 双写, publishDueEvents 扫这张索引表,
 * 命中后用 outboxShard + targetUserId 精确路由到 message_outbox.
 */
@Data
@TableName("outbox_pending")
public class OutboxPending {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long outboxId;
    private Integer outboxShard;
    private Long targetUserId;
    private String topic;
    private String msgId;
    private Integer status;
    private Integer retryCount;
    private LocalDateTime nextRetryAt;
    private LocalDateTime createdAt;
}
