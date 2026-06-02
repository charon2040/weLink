#!/bin/sh
set -eu

DB_NAME="${MYSQL_DATABASE:?MYSQL_DATABASE is required}"
MESSAGE_SHARD_START_MONTH="${MESSAGE_SHARD_START_MONTH:-202501}"
MESSAGE_SHARD_END_MONTH="${MESSAGE_SHARD_END_MONTH:-203012}"
MYSQL_CMD="mysql -uroot -p${MYSQL_ROOT_PASSWORD}"

${MYSQL_CMD} <<SQL
CREATE DATABASE IF NOT EXISTS \`${DB_NAME}\` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS \`${DB_NAME}\`.\`group_info\` (
  \`id\` BIGINT PRIMARY KEY AUTO_INCREMENT,
  \`group_name\` VARCHAR(100) NOT NULL,
  \`group_no\` VARCHAR(16) DEFAULT NULL COMMENT '群号 8 位数字',
  \`avatar\` VARCHAR(255),
  \`owner_id\` BIGINT NOT NULL,
  \`notice\` TEXT,
  \`member_count\` INT DEFAULT 0,
  \`status\` TINYINT DEFAULT 1,
  \`created_at\` DATETIME DEFAULT CURRENT_TIMESTAMP,
  \`updated_at\` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY \`uk_group_no\` (\`group_no\`),
  INDEX \`idx_owner_id\` (\`owner_id\`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SQL

cursor_month="${MESSAGE_SHARD_START_MONTH}"
while [ "${cursor_month}" -le "${MESSAGE_SHARD_END_MONTH}" ]; do
  ${MYSQL_CMD} <<SQL
CREATE TABLE IF NOT EXISTS \`${DB_NAME}\`.\`message_${cursor_month}\` (
  \`id\` BIGINT NOT NULL,
  \`msg_id\` VARCHAR(64) NOT NULL,
  \`conversation_id\` BIGINT NOT NULL,
  \`from_user_id\` BIGINT NOT NULL,
  \`to_user_id\` BIGINT,
  \`group_id\` BIGINT,
  \`group_seq\` BIGINT,
  \`conversation_seq\` BIGINT,
  \`msg_type\` TINYINT NOT NULL,
  \`content\` TEXT NOT NULL,
  \`status\` TINYINT DEFAULT 0,
  \`created_at\` DATETIME DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (\`id\`),
  UNIQUE KEY \`uk_msg_id\` (\`msg_id\`),
  KEY \`idx_conversation_id\` (\`conversation_id\`),
  KEY \`idx_created\` (\`created_at\`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
SQL

  year_part=$(printf '%s' "${cursor_month}" | cut -c1-4)
  month_part=$(printf '%s' "${cursor_month}" | cut -c5-6)
  month_part="${month_part#0}"
  [ -z "${month_part}" ] && month_part=0

  if [ "${month_part}" -eq 12 ]; then
    year_part=$((year_part + 1))
    month_part=1
  else
    month_part=$((month_part + 1))
  fi

  cursor_month=$(printf '%04d%02d' "${year_part}" "${month_part}")
done

i=0
while [ "$i" -lt 64 ]; do
  suffix=$(printf "%02d" "$i")
  ${MYSQL_CMD} <<SQL
CREATE TABLE IF NOT EXISTS \`${DB_NAME}\`.\`message_inbox_${suffix}\` (
  \`id\` BIGINT NOT NULL,
  \`msg_id\` VARCHAR(64) NOT NULL,
  \`receiver_id\` BIGINT NOT NULL,
  \`conversation_id\` BIGINT DEFAULT NULL,
  \`conversation_type\` TINYINT NOT NULL,
  \`status\` TINYINT NOT NULL DEFAULT 0,
  \`created_at\` DATETIME DEFAULT CURRENT_TIMESTAMP,
  \`updated_at\` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (\`id\`),
  UNIQUE KEY \`uk_msg_receiver\` (\`msg_id\`, \`receiver_id\`),
  KEY \`idx_receiver_status_created\` (\`receiver_id\`, \`status\`, \`created_at\`),
  KEY \`idx_receiver_conversation\` (\`receiver_id\`, \`conversation_id\`, \`created_at\`),
  KEY \`idx_msg_id\` (\`msg_id\`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS \`${DB_NAME}\`.\`message_outbox_${suffix}\` (
  \`id\` BIGINT NOT NULL,
  \`msg_id\` VARCHAR(64) NOT NULL,
  \`target_user_id\` BIGINT NOT NULL,
  \`topic\` VARCHAR(64) NOT NULL,
  \`conversation_id\` BIGINT DEFAULT NULL,
  \`message_created_at\` DATETIME DEFAULT NULL,
  \`status\` TINYINT NOT NULL DEFAULT 0,
  \`retry_count\` INT NOT NULL DEFAULT 0,
  \`next_retry_at\` DATETIME DEFAULT CURRENT_TIMESTAMP,
  \`last_error\` VARCHAR(512) DEFAULT NULL,
  \`created_at\` DATETIME DEFAULT CURRENT_TIMESTAMP,
  \`updated_at\` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (\`id\`),
  UNIQUE KEY \`uk_msg_target_topic\` (\`msg_id\`, \`target_user_id\`, \`topic\`),
  KEY \`idx_status_next_retry\` (\`status\`, \`next_retry_at\`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS \`${DB_NAME}\`.\`delivery_receipt_${suffix}\` (
  \`id\` BIGINT NOT NULL,
  \`msg_id\` VARCHAR(64) NOT NULL,
  \`receiver_id\` BIGINT NOT NULL,
  \`status\` TINYINT NOT NULL DEFAULT 0,
  \`delivered_at\` DATETIME DEFAULT NULL,
  \`read_at\` DATETIME DEFAULT NULL,
  \`created_at\` DATETIME DEFAULT CURRENT_TIMESTAMP,
  \`updated_at\` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (\`id\`),
  UNIQUE KEY \`uk_msg_receiver\` (\`msg_id\`, \`receiver_id\`),
  KEY \`idx_receiver_status\` (\`receiver_id\`, \`status\`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
SQL
  i=$((i + 1))
done
