-- =============================================================================
-- Sharding DDL Generator — Creates monthly partition tables ahead of time
-- Run this monthly to pre-create next month's tables across all 8 shards.
-- Example: CALL createMessageShards('202605');
-- =============================================================================

DELIMITER $$

CREATE PROCEDURE IF NOT EXISTS createMessageShards(IN monthSuffix VARCHAR(6))
BEGIN
    DECLARE i INT DEFAULT 0;
    DECLARE shardDb VARCHAR(64);
    DECLARE createSQL TEXT;

    WHILE i < 8 DO
        SET shardDb = CONCAT('welink_msg_0', i);

        SET createSQL = CONCAT(
            'CREATE TABLE IF NOT EXISTS ', shardDb, '.message_', monthSuffix, ' (
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
                UNIQUE KEY uk_conv_seq (conversation_id, conversation_seq),
                KEY idx_group_seq (group_id, group_seq),
                KEY idx_created (created_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4'
        );

        SET @sql = createSQL;
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;

        SET i = i + 1;
    END WHILE;
END$$

CREATE PROCEDURE IF NOT EXISTS createInboxShards(IN shards INT)
BEGIN
    DECLARE d INT DEFAULT 0;
    DECLARE t INT DEFAULT 0;
    DECLARE shardDb VARCHAR(64);
    DECLARE createSQL TEXT;

    WHILE d < 8 DO
        SET shardDb = CONCAT('welink_msg_0', d);
        SET t = 0;
        WHILE t < shards DO
            SET createSQL = CONCAT(
                'CREATE TABLE IF NOT EXISTS ', shardDb, '.message_inbox_', LPAD(t, 2, '0'), ' (
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
                    KEY idx_receiver_status_created (receiver_id, status, created_at),
                    KEY idx_receiver_conversation (receiver_id, conversation_id, created_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4'
            );

            SET @sql = createSQL;
            PREPARE stmt FROM @sql;
            EXECUTE stmt;
            DEALLOCATE PREPARE stmt;

            SET t = t + 1;
        END WHILE;
        SET d = d + 1;
    END WHILE;
END$$

CREATE PROCEDURE IF NOT EXISTS createOutboxShards(IN shards INT)
BEGIN
    DECLARE d INT DEFAULT 0;
    DECLARE t INT DEFAULT 0;
    DECLARE shardDb VARCHAR(64);
    DECLARE createSQL TEXT;

    WHILE d < 8 DO
        SET shardDb = CONCAT('welink_msg_0', d);
        SET t = 0;
        WHILE t < shards DO
            SET createSQL = CONCAT(
                'CREATE TABLE IF NOT EXISTS ', shardDb, '.message_outbox_', LPAD(t, 2, '0'), ' (
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
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4'
            );

            SET @sql = createSQL;
            PREPARE stmt FROM @sql;
            EXECUTE stmt;
            DEALLOCATE PREPARE stmt;

            SET t = t + 1;
        END WHILE;
        SET d = d + 1;
    END WHILE;
END$$

CREATE PROCEDURE IF NOT EXISTS createReceiptShards(IN shards INT)
BEGIN
    DECLARE d INT DEFAULT 0;
    DECLARE t INT DEFAULT 0;
    DECLARE shardDb VARCHAR(64);
    DECLARE createSQL TEXT;

    WHILE d < 8 DO
        SET shardDb = CONCAT('welink_msg_0', d);
        SET t = 0;
        WHILE t < shards DO
            SET createSQL = CONCAT(
                'CREATE TABLE IF NOT EXISTS ', shardDb, '.delivery_receipt_', LPAD(t, 2, '0'), ' (
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
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4'
            );

            SET @sql = createSQL;
            PREPARE stmt FROM @sql;
            EXECUTE stmt;
            DEALLOCATE PREPARE stmt;

            SET t = t + 1;
        END WHILE;
        SET d = d + 1;
    END WHILE;
END$$

DELIMITER ;

-- =============================================================================
-- Usage examples:
--   CALL createMessageShards('202606');  -- creates message_202606 on all 8 shards
--   CALL createInboxShards(64);          -- creates message_inbox_00..63 on all 8 shards
--   CALL createOutboxShards(64);         -- creates message_outbox_00..63 on all 8 shards
--   CALL createReceiptShards(64);        -- creates delivery_receipt_00..63 on all 8 shards
-- =============================================================================
