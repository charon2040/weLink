ALTER TABLE `group_member`
ADD COLUMN `last_read_seq` BIGINT NOT NULL DEFAULT 0 COMMENT '群已读游标' AFTER `role`;
