param(
    [switch]$KeepUsers
)

$ErrorActionPreference = "Stop"
$mysqlExe = "D:\MySQL\MySQL Server 8.0\bin\mysql.exe"
$dbUser = "root"
$dbPass = "123456"

if (-not (Test-Path $mysqlExe)) {
    Write-Host "找不到 mysql.exe: $mysqlExe" -ForegroundColor Red
    exit 1
}

function Invoke-Mysql {
    param([int]$Port, [string]$Database, [string]$Sql)
    $result = & $mysqlExe -h 127.0.0.1 -P $Port -u $dbUser -p$dbPass -D $Database -e $Sql 2>&1
    $result | Where-Object { $_ -notmatch 'Warning.*password' }
}

Write-Host ""
Write-Host ("=" * 70) -ForegroundColor Yellow
Write-Host "  WeLink 数据库清理 (DROP + CREATE 模式)" -ForegroundColor Yellow
Write-Host ("=" * 70) -ForegroundColor Yellow

# === 清理主库 (port 3315) ===
Write-Host ""
Write-Host "  [主库] 清理 conversation / read_cursor / outbox_pending ..." -ForegroundColor Cyan

Invoke-Mysql -Port 3315 -Database "welink" -Sql @"
DROP TABLE IF EXISTS conversation;
CREATE TABLE conversation (
   id BIGINT PRIMARY KEY AUTO_INCREMENT,
   biz_type TINYINT NOT NULL,
   owner_key VARCHAR(128) NOT NULL,
   last_msg_id VARCHAR(64) DEFAULT NULL,
   last_seq BIGINT NOT NULL DEFAULT 0,
   last_message_at BIGINT NOT NULL DEFAULT 0,
   created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
   updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
   UNIQUE KEY uk_owner_key (biz_type, owner_key),
   INDEX idx_last_message_at (last_message_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
"@

Invoke-Mysql -Port 3315 -Database "welink" -Sql @"
DROP TABLE IF EXISTS read_cursor;
CREATE TABLE read_cursor (
   id BIGINT PRIMARY KEY AUTO_INCREMENT,
   user_id BIGINT NOT NULL,
   conversation_key VARCHAR(128) NOT NULL,
   read_seq BIGINT NOT NULL DEFAULT 0,
   updated_at BIGINT NOT NULL DEFAULT 0,
   UNIQUE KEY uk_user_conv (user_id, conversation_key),
   INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
"@

Invoke-Mysql -Port 3315 -Database "welink" -Sql @"
DROP TABLE IF EXISTS outbox_pending;
CREATE TABLE outbox_pending (
   id BIGINT AUTO_INCREMENT PRIMARY KEY,
   outbox_id BIGINT NOT NULL,
   outbox_shard TINYINT NOT NULL,
   target_user_id BIGINT NOT NULL,
   topic VARCHAR(64) NOT NULL,
   msg_id VARCHAR(64) NOT NULL,
   status TINYINT NOT NULL DEFAULT 0,
   retry_count INT NOT NULL DEFAULT 0,
   next_retry_at DATETIME NOT NULL,
   created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
   UNIQUE KEY uk_outbox_id (outbox_id),
   KEY idx_status_retry (status, next_retry_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
"@

if (-not $KeepUsers) {
    Write-Host "  [主库] 清理 friend_relation / group_member / group_info / user ..." -ForegroundColor Cyan
    Invoke-Mysql -Port 3315 -Database "welink" -Sql "SET FOREIGN_KEY_CHECKS=0; DROP TABLE IF EXISTS friend_relation; DROP TABLE IF EXISTS group_member; DROP TABLE IF EXISTS group_info; DROP TABLE IF EXISTS user; SET FOREIGN_KEY_CHECKS=1;"

    Invoke-Mysql -Port 3315 -Database "welink" -Sql @"
CREATE TABLE user (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  username VARCHAR(50) UNIQUE NOT NULL,
  password VARCHAR(100) NOT NULL,
  nickname VARCHAR(50),
  avatar VARCHAR(255),
  email VARCHAR(100),
  phone VARCHAR(20),
  status TINYINT DEFAULT 1,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_username (username),
  INDEX idx_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
"@

    Invoke-Mysql -Port 3315 -Database "welink" -Sql @"
CREATE TABLE friend_relation (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  friend_id BIGINT NOT NULL,
  status TINYINT DEFAULT 0,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_user_friend (user_id, friend_id),
  INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
"@

    Invoke-Mysql -Port 3315 -Database "welink" -Sql @"
CREATE TABLE group_info (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
"@

    Invoke-Mysql -Port 3315 -Database "welink" -Sql @"
CREATE TABLE group_member (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  group_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  role TINYINT DEFAULT 0,
  last_read_seq BIGINT NOT NULL DEFAULT 0,
  join_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_group_user (group_id, user_id),
  INDEX idx_group_id (group_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
"@
} else {
    Write-Host "  [主库] 保留 user / friend_relation / group_member / group_info" -ForegroundColor Green
}

# === 清理分片库 (port 3307~3314) ===
# 分片库的 message 表按月分表, 直接 DROP + CREATE 最快
foreach ($i in 0..7) {
    $port = 3307 + $i
    $db = "welink_msg_$($i.ToString('00'))"
    Write-Host ""
    Write-Host "  [分片$i] 清理 $db ..." -ForegroundColor Cyan

    $tablesRaw = Invoke-Mysql -Port $port -Database $db -Sql "SHOW TABLES;"
    $tableList = $tablesRaw | Where-Object { $_ -match '^(message_|message_inbox_|message_outbox_|delivery_receipt_|group_info)' }

    $dropSqls = @()
    foreach ($tbl in $tableList) {
        $tbl = $tbl.Trim()
        if ($tbl -and $tbl -notmatch '^Tables_in_') {
            $dropSqls += "DROP TABLE IF EXISTS $tbl;"
        }
    }

    if ($dropSqls.Count -gt 0) {
        Write-Host "    删除 $($dropSqls.Count) 张表 ..." -ForegroundColor DarkGray
        $allSql = $dropSqls -join "`n"
        Invoke-Mysql -Port $port -Database $db -Sql $allSql
    }
}

Write-Host ""
Write-Host ("=" * 70) -ForegroundColor Green
Write-Host "  数据库清理完成" -ForegroundColor Green
Write-Host ("=" * 70) -ForegroundColor Green
