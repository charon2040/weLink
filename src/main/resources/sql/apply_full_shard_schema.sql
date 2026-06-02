-- 完整 shard schema (含 outbox 的 conversation_id / message_created_at + 路由列)
-- 跑一次即可: docker exec welink-shard-mysql sh -c "mysql -uroot -p123456 < /tmp/full.sql"

USE welink;

-- 在 8 个分库各建一份: message_202501..203012 + message_inbox_00..63 + message_outbox_00..63 + delivery_receipt_00..63
-- 与 sharding-config.yaml 的 message actualDataNodes 范围保持一致, 避免配置已放开但物理表未预建

DROP PROCEDURE IF EXISTS applyVerifySchema;
DELIMITER $$
CREATE PROCEDURE applyVerifySchema()
BEGIN
    DECLARE i INT DEFAULT 0;
    DECLARE shardDb VARCHAR(64);

    WHILE i < 8 DO
        SET shardDb = CONCAT('welink_msg_0', i);

        -- group_info (BROADCAST 表, 每个 shard 必有)
        SET @sql = CONCAT(
            'CREATE TABLE IF NOT EXISTS ', shardDb, '.group_info (
                id BIGINT PRIMARY KEY AUTO_INCREMENT,
                group_name VARCHAR(100) NOT NULL,
                group_no VARCHAR(16) DEFAULT NULL,
                avatar VARCHAR(255),
                owner_id BIGINT NOT NULL,
                notice TEXT,
                member_count INT DEFAULT 0,
                status TINYINT DEFAULT 1,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                UNIQUE KEY uk_group_no (group_no),
                INDEX idx_owner_id (owner_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4');
        PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

        BLOCK_MSG: BEGIN
            DECLARE monthCursor INT DEFAULT 202501;
            DECLARE yearPart INT;
            DECLARE monthPart INT;

            WHILE monthCursor <= 203012 DO
                SET @sql = CONCAT(
                    'CREATE TABLE IF NOT EXISTS ', shardDb, '.message_', monthCursor, ' (
                        id BIGINT NOT NULL,
                        msg_id VARCHAR(64) NOT NULL,
                        conversation_id BIGINT NOT NULL,
                        from_user_id BIGINT NOT NULL,
                        to_user_id BIGINT,
                        group_id BIGINT,
                        group_seq BIGINT,
                        conversation_seq BIGINT,
                        msg_type TINYINT NOT NULL,
                        content TEXT NOT NULL,
                        status TINYINT DEFAULT 0,
                        created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (id),
                        UNIQUE KEY uk_msg_id (msg_id),
                        KEY idx_conversation_id (conversation_id),
                        KEY idx_created (created_at)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4');
                PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

                SET yearPart = FLOOR(monthCursor / 100);
                SET monthPart = MOD(monthCursor, 100);
                IF monthPart = 12 THEN
                    SET monthCursor = (yearPart + 1) * 100 + 1;
                ELSE
                    SET monthCursor = yearPart * 100 + monthPart + 1;
                END IF;
            END WHILE;
        END BLOCK_MSG;

        -- 64 张 inbox
        BLOCK1: BEGIN
            DECLARE t INT DEFAULT 0;
            WHILE t < 64 DO
                SET @sql = CONCAT(
                    'CREATE TABLE IF NOT EXISTS ', shardDb, '.message_inbox_', t, ' (
                        id BIGINT NOT NULL,
                        msg_id VARCHAR(64) NOT NULL,
                        receiver_id BIGINT NOT NULL,
                        conversation_id BIGINT NOT NULL,
                        conversation_type TINYINT NOT NULL,
                        status TINYINT NOT NULL DEFAULT 0,
                        created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                        updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                        PRIMARY KEY (id),
                        UNIQUE KEY uk_msg_receiver (msg_id, receiver_id),
                        KEY idx_receiver_status_created (receiver_id, status, created_at)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4');
                PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
                SET t = t + 1;
            END WHILE;
        END BLOCK1;

        -- 64 张 outbox
        BLOCK2: BEGIN
            DECLARE t INT DEFAULT 0;
            WHILE t < 64 DO
                SET @sql = CONCAT(
                    'CREATE TABLE IF NOT EXISTS ', shardDb, '.message_outbox_', t, ' (
                        id BIGINT NOT NULL,
                        msg_id VARCHAR(64) NOT NULL,
                        target_user_id BIGINT NOT NULL,
                        topic VARCHAR(64) NOT NULL,
                        conversation_id BIGINT DEFAULT NULL,
                        message_created_at DATETIME DEFAULT NULL,
                        status TINYINT NOT NULL DEFAULT 0,
                        retry_count INT NOT NULL DEFAULT 0,
                        next_retry_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                        last_error VARCHAR(512) DEFAULT NULL,
                        created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                        updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                        PRIMARY KEY (id),
                        UNIQUE KEY uk_msg_target_topic (msg_id, target_user_id, topic),
                        KEY idx_status_next_retry (status, next_retry_at)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4');
                PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
                SET t = t + 1;
            END WHILE;
        END BLOCK2;

        -- 64 张 delivery_receipt
        BLOCK3: BEGIN
            DECLARE t INT DEFAULT 0;
            WHILE t < 64 DO
                SET @sql = CONCAT(
                    'CREATE TABLE IF NOT EXISTS ', shardDb, '.delivery_receipt_', t, ' (
                        id BIGINT NOT NULL,
                        msg_id VARCHAR(64) NOT NULL,
                        receiver_id BIGINT NOT NULL,
                        status TINYINT NOT NULL DEFAULT 0,
                        delivered_at DATETIME DEFAULT NULL,
                        read_at DATETIME DEFAULT NULL,
                        created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                        updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                        PRIMARY KEY (id),
                        UNIQUE KEY uk_msg_receiver (msg_id, receiver_id),
                        KEY idx_receiver_status (receiver_id, status)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4');
                PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
                SET t = t + 1;
            END WHILE;
        END BLOCK3;

        SET i = i + 1;
    END WHILE;
END$$
DELIMITER ;

CALL applyVerifySchema();
DROP PROCEDURE applyVerifySchema;

-- 在 unsharded 默认库 welink 建 group_info (广播表) 和其他单表
USE welink;

CREATE TABLE IF NOT EXISTS `group_info` (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    group_name VARCHAR(100) NOT NULL,
    group_no VARCHAR(16) DEFAULT NULL,
    avatar VARCHAR(255),
    owner_id BIGINT NOT NULL,
    notice TEXT,
    member_count INT DEFAULT 0,
    status TINYINT DEFAULT 1,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_group_no (group_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
