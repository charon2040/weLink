# DB 直插好友关系 + 写 private-pairs.csv, 跳过 HTTP API
# 用法: pwsh prepare-db-pairs.ps1 -Pairs 1000
#
# 流程:
#   1. 读 tokens.csv (前置: prepare-db-bulk.ps1)
#   2. 用前 2N 个账号两两配对
#   3. SQL 直插 friend_relation 双向 status=1 已为好友
#   4. 写 private-pairs.csv

param(
    [int]$Pairs = 1000,
    [string]$MysqlContainer = "welink-mysql-main"
)

$ErrorActionPreference = "Stop"
$dataDir = Join-Path $PSScriptRoot ".." "data"
$tokensCsv = Join-Path $dataDir "tokens.csv"
$pairsCsv = Join-Path $dataDir "private-pairs.csv"

if (-not (Test-Path $tokensCsv)) {
    Write-Host "缺少 $tokensCsv, 先运行 prepare-db-bulk.ps1" -ForegroundColor Red
    exit 1
}

$tokens = Import-Csv $tokensCsv
if ($tokens.Count -lt ($Pairs * 2)) {
    Write-Host "tokens 池 $($tokens.Count) 不够 $Pairs 对 (需 $($Pairs * 2)). 用 prepare-db-bulk.ps1 -Count $($Pairs * 2 + 100) 扩容" -ForegroundColor Red
    exit 1
}

Write-Host "==== DB 直插配对 $Pairs 对好友 ====" -ForegroundColor Cyan

# 生成 SQL: 每对插 2 行 (a→b, b→a 双向 status=1 已是好友)
$sb = New-Object System.Text.StringBuilder
$null = $sb.AppendLine("USE welink;")
# 清掉历史压测好友关系
$null = $sb.AppendLine("DELETE FROM friend_relation WHERE user_id IN (SELECT id FROM user WHERE username LIKE 'perf_user_%') OR friend_id IN (SELECT id FROM user WHERE username LIKE 'perf_user_%');")

$values = @()
for ($i = 0; $i -lt $Pairs; $i++) {
    $a = $tokens[$i * 2]
    $b = $tokens[$i * 2 + 1]
    $values += "($($a.userId),$($b.userId),1)"
    $values += "($($b.userId),$($a.userId),1)"
    # 每 500 行一批
    if ($values.Count -ge 500) {
        $null = $sb.AppendLine("INSERT INTO friend_relation (user_id, friend_id, status) VALUES " + ($values -join ',') + ";")
        $values = @()
    }
}
if ($values.Count -gt 0) {
    $null = $sb.AppendLine("INSERT INTO friend_relation (user_id, friend_id, status) VALUES " + ($values -join ',') + ";")
}

# 执行 SQL
$tmp = New-TemporaryFile
$sb.ToString() | Out-File $tmp.FullName -Encoding utf8 -NoNewline
docker cp $tmp.FullName "${MysqlContainer}:/tmp/_pairs.sql" | Out-Null
docker exec $MysqlContainer sh -c "mysql -uroot -p123456 -BN < /tmp/_pairs.sql 2>/dev/null" | Out-Null
Remove-Item $tmp.FullName

Write-Host "  DB 插入完成"

# 写 CSV
"aUsername,aUserId,aToken,bUsername,bUserId,bToken" | Out-File -FilePath $pairsCsv -Encoding utf8
for ($i = 0; $i -lt $Pairs; $i++) {
    $a = $tokens[$i * 2]
    $b = $tokens[$i * 2 + 1]
    "$($a.username),$($a.userId),$($a.accessToken),$($b.username),$($b.userId),$($b.accessToken)" | Add-Content -Path $pairsCsv -Encoding utf8
}

Write-Host ""
Write-Host "==== 完成 ====" -ForegroundColor Green
Write-Host "配对数:   $Pairs"
Write-Host "CSV:      $pairsCsv"
