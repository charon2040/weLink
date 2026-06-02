-- File metadata table (2026-05-26)
-- 非分片, 在 welink 库. 让消息里的 content 存稳定的 fileId 而不是会 7 天过期的 presigned URL.

USE welink;

CREATE TABLE IF NOT EXISTS `file_meta` (
   `id`                BIGINT AUTO_INCREMENT PRIMARY KEY,
   `file_id`           VARCHAR(36) NOT NULL              COMMENT 'UUID v4, 对外暴露的稳定 ID',
   `object_name`       VARCHAR(255) NOT NULL             COMMENT 'MinIO bucket 内的对象名',
   `original_filename` VARCHAR(255)                      COMMENT '原始文件名',
   `size`              BIGINT                            COMMENT '字节数',
   `mime_type`         VARCHAR(100)                      COMMENT 'Content-Type',
   `uploader_id`       BIGINT                            COMMENT '上传者 user_id (可空)',
   `created_at`        DATETIME DEFAULT CURRENT_TIMESTAMP,
   UNIQUE KEY `uk_file_id` (`file_id`),
   KEY `idx_uploader` (`uploader_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文件元数据表';
