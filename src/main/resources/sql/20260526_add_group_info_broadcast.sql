-- group_info 是 BROADCAST 表 (sharding-config.yaml !BROADCAST), 要求每个 ds 都有
-- apply_full_shard_schema.sql 只在 8 个 shard 库建了 message 类表, 漏了 group_info
-- 这个迁移脚本在 8 个 shard 库各建一份 group_info, 让 broadcast 写入能成功

USE welink;

DELIMITER $$
DROP PROCEDURE IF EXISTS createGroupInfoBroadcast$$
CREATE PROCEDURE createGroupInfoBroadcast()
BEGIN
    DECLARE i INT DEFAULT 0;
    DECLARE shardDb VARCHAR(64);
    WHILE i < 8 DO
        SET shardDb = CONCAT('welink_msg_0', i);
        SET @sql = CONCAT(
            'CREATE TABLE IF NOT EXISTS ', shardDb, '.group_info (
                id BIGINT PRIMARY KEY AUTO_INCREMENT,
                group_name VARCHAR(100) NOT NULL,
                avatar VARCHAR(255),
                owner_id BIGINT NOT NULL,
                notice TEXT,
                member_count INT DEFAULT 0,
                status TINYINT DEFAULT 1,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                INDEX idx_owner_id (owner_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4');
        PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
        SET i = i + 1;
    END WHILE;
END$$
DELIMITER ;

CALL createGroupInfoBroadcast();
DROP PROCEDURE createGroupInfoBroadcast;
