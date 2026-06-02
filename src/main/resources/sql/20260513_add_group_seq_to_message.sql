ALTER TABLE `message`
ADD COLUMN `group_seq` BIGINT DEFAULT NULL COMMENT '群聊单调递增序号' AFTER `group_id`;

CREATE INDEX `idx_group_seq` ON `message` (`group_id`, `group_seq`);
