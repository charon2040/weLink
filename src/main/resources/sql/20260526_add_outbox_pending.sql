-- Outbox pending index table (2026-05-26)
-- 非分片, 装在 welink 库. 仅作为 "待发布 / 失败待重试" 的轻量索引,
-- 让 outbox 轮询从 "broadcast 8 ds × 64 表" 变成 "单表索引扫描".
-- 完整账本仍是分片的 message_outbox.

USE welink;

CREATE TABLE IF NOT EXISTS `outbox_pending` (
   `id`             BIGINT      AUTO_INCREMENT PRIMARY KEY,
   `outbox_id`      BIGINT      NOT NULL                COMMENT '对应 message_outbox.id',
   `outbox_shard`   TINYINT     NOT NULL                COMMENT 'target_user_id % 8, 路由 message_outbox 用',
   `target_user_id` BIGINT      NOT NULL                COMMENT '冗余: Kafka key + table 路由',
   `topic`          VARCHAR(64) NOT NULL                COMMENT 'Kafka topic',
   `msg_id`         VARCHAR(64) NOT NULL                COMMENT '消息 ID',
   `status`         TINYINT     NOT NULL DEFAULT 0      COMMENT '0=PENDING 1=FAILED',
   `retry_count`    INT         NOT NULL DEFAULT 0      COMMENT '重试次数',
   `next_retry_at`  DATETIME    NOT NULL                COMMENT '下次重试时间',
   `created_at`     DATETIME    DEFAULT CURRENT_TIMESTAMP,
   UNIQUE KEY `uk_outbox_id` (`outbox_id`),
   KEY `idx_status_retry` (`status`, `next_retry_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Outbox 待发布索引表';
