-- Phase 1.1 Migration: Add routing columns to message_outbox
-- Date: 2026-05-25
-- 目的: 避免 outbox publisher 查 message 表时全分片扫描
-- 用法: 在单库 welink + 每个分库 welink_msg_00..07 都执行一次

ALTER TABLE message_outbox
    ADD COLUMN conversation_id BIGINT DEFAULT NULL COMMENT '冗余: 用于路由 message 表查询' AFTER topic,
    ADD COLUMN message_created_at DATETIME DEFAULT NULL COMMENT '冗余: 用于路由到 message 月分区表' AFTER conversation_id;

-- 回填: 从 message 表关联补充
UPDATE message_outbox o
    INNER JOIN message m ON o.msg_id = m.msg_id
SET o.conversation_id = m.conversation_id,
    o.message_created_at = m.created_at
WHERE o.conversation_id IS NULL;
