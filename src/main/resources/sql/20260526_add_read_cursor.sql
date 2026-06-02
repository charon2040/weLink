-- Manual migration: add read_cursor table (2026-05-26)
-- 在 welink 库执行

USE welink;

CREATE TABLE IF NOT EXISTS `read_cursor` (
   `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
   `user_id` BIGINT NOT NULL COMMENT '用户ID',
   `conversation_key` VARCHAR(128) NOT NULL COMMENT '会话Key',
   `read_seq` BIGINT NOT NULL DEFAULT 0 COMMENT '已读序号',
   `updated_at` BIGINT NOT NULL DEFAULT 0 COMMENT '更新时间(epoch ms)',
   UNIQUE KEY `uk_user_conv` (`user_id`, `conversation_key`),
   INDEX `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话已读游标表';
