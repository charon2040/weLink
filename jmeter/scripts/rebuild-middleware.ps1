param(
    [int]$WaitSeconds = 60
)

$ErrorActionPreference = "Stop"
$composeFile = Join-Path (Resolve-Path (Join-Path $PSScriptRoot "..\..")) "docker-compose.multi-mysql.yml"

Write-Host ""
Write-Host ("=" * 70) -ForegroundColor Yellow
Write-Host "  WeLink 中间件一键重建" -ForegroundColor Yellow
Write-Host ("=" * 70) -ForegroundColor Yellow

Write-Host ""
Write-Host "  [1/5] 停止并删除所有容器和数据卷 ..." -ForegroundColor Cyan
docker compose -f $composeFile down -v 2>&1
if ($LASTEXITCODE -ne 0) {
    Write-Host "  docker compose down 失败, 尝试逐个删除 ..." -ForegroundColor Yellow
    $containers = @(
        "welink-mysql-shard-0", "welink-mysql-shard-1", "welink-mysql-shard-2",
        "welink-mysql-shard-3", "welink-mysql-shard-4", "welink-mysql-shard-5",
        "welink-mysql-shard-6", "welink-mysql-shard-7", "welink-mysql-main",
        "welink-redis", "welink-kafka", "welink-minio"
    )
    foreach ($c in $containers) {
        Write-Host "    停止 $c ..." -ForegroundColor DarkGray
        docker stop $c 2>$null
        docker rm -f $c 2>$null
    }
    $volumes = @(
        "welink_mysql-shard-0-data", "welink_mysql-shard-1-data", "welink_mysql-shard-2-data",
        "welink_mysql-shard-3-data", "welink_mysql-shard-4-data", "welink_mysql-shard-5-data",
        "welink_mysql-shard-6-data", "welink_mysql-shard-7-data", "welink_mysql-main-data",
        "welink_minio-data"
    )
    foreach ($v in $volumes) {
        Write-Host "    删除卷 $v ..." -ForegroundColor DarkGray
        docker volume rm -f $v 2>$null
    }
}

Write-Host ""
Write-Host "  [2/5] 重新启动所有中间件 ..." -ForegroundColor Cyan
docker compose -f $composeFile up -d 2>&1
if ($LASTEXITCODE -ne 0) {
    Write-Host "  docker compose up 失败!" -ForegroundColor Red
    exit 1
}

Write-Host ""
Write-Host "  [3/5] 等待 MySQL 初始化 ($WaitSeconds 秒) ..." -ForegroundColor Cyan
Start-Sleep -Seconds $WaitSeconds

Write-Host ""
Write-Host "  [4/5] 检查各服务状态 ..." -ForegroundColor Cyan
$mysqlExe = "D:\MySQL\MySQL Server 8.0\bin\mysql.exe"
$allOk = $true

foreach ($i in 0..7) {
    $port = 3307 + $i
    $db = "welink_msg_$($i.ToString('00'))"
    $result = & $mysqlExe -h 127.0.0.1 -P $port -u root -p123456 --connect-timeout=5 -e "SELECT 1;" 2>&1
    if ($result -match 'test|1') {
        Write-Host "    [OK] 分片$i ($db :$port)" -ForegroundColor Green
    } else {
        Write-Host "    [FAIL] 分片$i ($db :$port)" -ForegroundColor Red
        $allOk = $false
    }
}

$result = & $mysqlExe -h 127.0.0.1 -P 3315 -u root -p123456 --connect-timeout=5 -e "SELECT 1;" 2>&1
if ($result -match 'test|1') {
    Write-Host "    [OK] 主库 (welink :3315)" -ForegroundColor Green
} else {
    Write-Host "    [FAIL] 主库 (welink :3315)" -ForegroundColor Red
    $allOk = $false
}

$redisResult = redis-cli -h 127.0.0.1 -p 6379 ping 2>&1
if ($redisResult -match 'PONG') {
    Write-Host "    [OK] Redis (:6379)" -ForegroundColor Green
} else {
    Write-Host "    [WARN] Redis (:6379) - 可能需要安装 redis-cli" -ForegroundColor Yellow
}

Write-Host ""
if ($allOk) {
    Write-Host "  [5/5] 所有 MySQL 实例就绪!" -ForegroundColor Green
} else {
    Write-Host "  [5/5] 部分 MySQL 实例未就绪, 可能需要更多等待时间" -ForegroundColor Yellow
    Write-Host "  可用命令重试: pwsh -File jmeter\scripts\rebuild-middleware.ps1 -WaitSeconds 90" -ForegroundColor Yellow
}

Write-Host ""
Write-Host ("=" * 70) -ForegroundColor Green
Write-Host "  中间件重建完成" -ForegroundColor Green
Write-Host ("=" * 70) -ForegroundColor Green
