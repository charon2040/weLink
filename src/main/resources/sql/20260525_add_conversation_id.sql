-- Phase 1 Migration: Add conversation_id column for sharding
-- Date: 2026-05-25

-- Add conversation_id to message table
ALTER TABLE message
    ADD COLUMN conversation_id BIGINT DEFAULT NULL COMMENT '会话ID(分片键)' AFTER conversation_seq,
    ADD INDEX idx_conversation_id (conversation_id);

-- Backfill conversation_id for existing private messages
UPDATE message
SET conversation_id = (CAST(CONV(SUBSTRING(MD5(CONCAT('single:', LEAST(from_user_id, to_user_id), ':', GREATEST(from_user_id, to_user_id))), 1, 16), 16, 10) AS SIGNED) & 9223372036854775807)
WHERE to_user_id IS NOT NULL AND conversation_id IS NULL;

-- Backfill conversation_id for existing group messages
UPDATE message
SET conversation_id = (CAST(CONV(SUBSTRING(MD5(CONCAT('group:', group_id)), 1, 16), 16, 10) AS SIGNED) & 9223372036854775807)
WHERE group_id IS NOT NULL AND conversation_id IS NULL;

-- Add conversation_id to message_inbox table
ALTER TABLE message_inbox
    ADD COLUMN conversation_id BIGINT DEFAULT NULL COMMENT '会话ID(冗余)' AFTER receiver_id;
