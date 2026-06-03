# 完整重置: 停后端 → 清 DB → 清 Redis → 重启后端 → 重新准备数据
# 用法: pwsh reset-and-prepare.ps1 [-Users 10000] [-Pairs 1000] [-Members 1000]

param(
    [int]$Users = 10000,
    [int]$Pairs = 1000,
    [int]$Members = 1000,
    [string]$MysqlMainContainer = "welink-mysql-main",
    [string]$ShardMonth = (Get-Date -Format "yyyyMM")
)

$ErrorActionPreference = "Stop"
$root = Resolve-Path (Join-Path $PSScriptRoot "..")
$projectRoot = Resolve-Path (Join-Path $root "..")
$jarPath = Join-Path $projectRoot "target" "WeLink-0.0.1-SNAPSHOT.jar"
$MysqlShardContainers = 0..7 | ForEach-Object { "welink-mysql-shard-$_" }

function Run-SqlOnContainer {
    param(
        [string]$Container,
        [string]$Sql,
        [string]$TempName
    )
    $tmp = New-TemporaryFile
    $Sql | Out-File $tmp.FullName -Encoding utf8 -NoNewline
    docker cp $tmp.FullName "${Container}:/tmp/$TempName" | Out-Null
    docker exec $Container sh -c "mysql -uroot -p123456 < /tmp/$TempName 2>/dev/null" | Out-Null
    Remove-Item $tmp.FullName -Force
}

Write-Host ""
Write-Host ("=" * 70) -ForegroundColor Cyan
Write-Host "  完整重置 + 准备压测数据" -ForegroundColor Cyan
Write-Host ("=" * 70) -ForegroundColor Cyan

# Step 1: 停后端
Write-Host ""
Write-Host "→ Step 1: 停后端 java 进程" -ForegroundColor Yellow
$javaProcs = Get-Process java -ErrorAction SilentlyContinue
if ($javaProcs) {
    $javaProcs | Stop-Process -Force
    Write-Host "  killed $($javaProcs.Count) java process(es)"
} else {
    Write-Host "  无 java 进程在跑"
}
Start-Sleep 3

# Step 2: 清 DB 数据
Write-Host ""
Write-Host "→ Step 2: 清空 welink 库的压测数据" -ForegroundColor Yellow
$mainSql = @"
USE welink;
DELETE FROM friend_relation;
DELETE FROM group_member;
DELETE FROM group_info;
DELETE FROM conversation;
DELETE FROM read_cursor;
DELETE FROM outbox_pending;
DELETE FROM file_meta;
DELETE FROM user WHERE username LIKE 'perf_%' OR username LIKE 'smoke_%' OR username LIKE 'gtest_%' OR username LIKE 'shard_%';
"@
Run-SqlOnContainer -Container $MysqlMainContainer -Sql $mainSql -TempName "_reset-main.sql"

for ($i = 0; $i -lt $MysqlShardContainers.Count; $i++) {
    $db = "welink_msg_0$i"
    $shardSql = New-Object System.Text.StringBuilder
    $null = $shardSql.AppendLine("DELETE FROM ${db}.group_info;")
    $null = $shardSql.AppendLine("TRUNCATE TABLE ${db}.message_${ShardMonth};")
    for ($j = 0; $j -lt 64; $j++) {
        $suffix = "{0:D2}" -f $j
        $null = $shardSql.AppendLine("TRUNCATE TABLE ${db}.message_inbox_${suffix};")
        $null = $shardSql.AppendLine("TRUNCATE TABLE ${db}.message_outbox_${suffix};")
        $null = $shardSql.AppendLine("TRUNCATE TABLE ${db}.delivery_receipt_${suffix};")
    }
    Run-SqlOnContainer -Container $MysqlShardContainers[$i] -Sql $shardSql.ToString() -TempName "_reset-shard-$i.sql"
}

Write-Host "  ✓ 清 DB 完成"

# Step 3: 清 Redis
Write-Host ""
Write-Host "→ Step 3: 清 Redis 全部 key" -ForegroundColor Yellow
docker exec welink-redis redis-cli FLUSHDB | Out-Null
Write-Host "  ✓ Redis FLUSHDB"

# Step 4: 重启后端 (后台跑)
Write-Host ""
Write-Host "→ Step 4: 重启后端 java -jar (后台)" -ForegroundColor Yellow
if (-not (Test-Path $jarPath)) {
    Write-Host "  jar 不存在: $jarPath" -ForegroundColor Red
    exit 1
}
$logFile = Join-Path $projectRoot "backend.log"
Start-Process -FilePath "java" -ArgumentList "-Xms2g","-Xmx4g","-XX:+UseG1GC","-jar",$jarPath -RedirectStandardOutput $logFile -RedirectStandardError "$logFile.err" -WindowStyle Hidden -PassThru | Out-Null
Write-Host "  后端启动中, 等 30 秒..."
$ok = $false
for ($i = 1; $i -le 30; $i++) {
    Start-Sleep 1
    try {
        $h = (Invoke-WebRequest http://localhost:8080/actuator/health -UseBasicParsing -TimeoutSec 1 2>$null).StatusCode
        if ($h -eq 200) { $ok = $true; break }
    } catch { }
}
if ($ok) {
    Write-Host "  ✓ 后端 healthy (启动 $i 秒)"
} else {
    Write-Host "  ✗ 后端没起来, 看 $logFile" -ForegroundColor Red
    exit 1
}

# Step 5: 重新准备数据
Write-Host ""
Write-Host "→ Step 5: 准备压测数据 ($Users 账号 / $Pairs 对 / $Members 人群)" -ForegroundColor Yellow
& (Join-Path $PSScriptRoot "prepare-all.ps1") -Users $Users -Pairs $Pairs -Members $Members

Write-Host ""
Write-Host ("=" * 70) -ForegroundColor Green
Write-Host "  全部完成. 现在可以跑压测了:" -ForegroundColor Green
Write-Host "    pwsh stress-v2.ps1 -WsTarget 10000" -ForegroundColor Cyan
Write-Host ("=" * 70) -ForegroundColor Green
