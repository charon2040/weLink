-- 给 group_info 加 group_no 8 位群号 (2026-05-27)
-- MySQL 不支持 ALTER ADD COLUMN IF NOT EXISTS, 用 information_schema 判断

USE welink;

DELIMITER $$
DROP PROCEDURE IF EXISTS addGroupNoColumn$$
CREATE PROCEDURE addGroupNoColumn()
BEGIN
    DECLARE i INT DEFAULT -1;
    DECLARE schemaName VARCHAR(64);
    DECLARE colExists INT;
    DECLARE idxExists INT;

    WHILE i < 8 DO
        IF i = -1 THEN SET schemaName = 'welink';
        ELSE SET schemaName = CONCAT('welink_msg_0', i);
        END IF;

        -- 加列(不存在时)
        SELECT COUNT(*) INTO colExists FROM information_schema.columns
         WHERE table_schema = schemaName AND table_name = 'group_info' AND column_name = 'group_no';
        IF colExists = 0 THEN
            SET @sql = CONCAT('ALTER TABLE ', schemaName, '.group_info ADD COLUMN group_no VARCHAR(16) DEFAULT NULL COMMENT ''群号 8 位数字'' AFTER group_name');
            PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
        END IF;

        -- 回填历史群号 = id 末 8 位补零
        SET @sql = CONCAT('UPDATE ', schemaName, '.group_info SET group_no = LPAD(id MOD 100000000, 8, ''0'') WHERE group_no IS NULL');
        PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

        -- 加唯一索引(不存在时)
        SELECT COUNT(*) INTO idxExists FROM information_schema.statistics
         WHERE table_schema = schemaName AND table_name = 'group_info' AND index_name = 'uk_group_no';
        IF idxExists = 0 THEN
            SET @sql = CONCAT('ALTER TABLE ', schemaName, '.group_info ADD UNIQUE KEY uk_group_no (group_no)');
            PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
        END IF;

        SET i = i + 1;
    END WHILE;
END$$
DELIMITER ;

CALL addGroupNoColumn();
DROP PROCEDURE addGroupNoColumn;
