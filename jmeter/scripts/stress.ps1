# WeLink IM 压测 & 投递可靠性测试 - 一键执行
# 用法: pwsh stress.ps1 [-Quick] [-Only <plan>]
#   -Quick   快速模式 (小规模, 验证流程)
#   -Only    只跑指定 plan (01-06)
#
# 正常模式目标: 10000 msg/s
# 前置: 服务端 rate-limit 已调大 (send=200/s, group=100/s)

param(
    [switch]$Quick,
    [string]$Only
)

$ErrorActionPreference = "Stop"
$root = Resolve-Path (Join-Path $PSScriptRoot "..")
$summary = @()

function runPlan {
    param(
        [string]$Plan,
        [int]$Threads,
        [int]$Duration,
        [int]$Ramp,
        [string]$Extra = "",
        [string]$Goal = ""
    )
    Write-Host ""
    Write-Host ("=" * 70) -ForegroundColor Cyan
    Write-Host "  跑: $Plan  (目标: $Goal)" -ForegroundColor Cyan
    Write-Host ("=" * 70) -ForegroundColor Cyan
    $runScript = Join-Path $PSScriptRoot "run.ps1"
    & $runScript -Plan $Plan -Threads $Threads -Duration $Duration -Ramp $Ramp -ExtraArgs $Extra
    $script:summary += [PSCustomObject]@{
        Plan = $Plan
        Threads = $Threads
        Duration = "${Duration}s"
        Goal = $Goal
    }
}

Write-Host ""
Write-Host ("=" * 70) -ForegroundColor Yellow
Write-Host "  WeLink IM 压测 & 投递可靠性测试" -ForegroundColor Yellow
Write-Host ("=" * 70) -ForegroundColor Yellow

if ($Quick) {
    Write-Host "  [快速模式] 小规模验证" -ForegroundColor Yellow
} else {
    Write-Host "  目标: 10000 msg/s" -ForegroundColor Yellow
}

# === 数据准备检查 ===
$e2eDataDir = Join-Path $root "data"
$needE2E = -not (Test-Path (Join-Path $e2eDataDir "e2e-private-senders.csv")) `
         -or -not (Test-Path (Join-Path $e2eDataDir "e2e-group-senders.csv"))

if ($needE2E) {
    Write-Host ""
    Write-Host "  E2E 数据未就绪, 运行 prepare-e2e.ps1 ..." -ForegroundColor Yellow
    if ($Quick) {
        & (Join-Path $PSScriptRoot "prepare-e2e.ps1") -PvtSenders 10 -PvtReceivers 10 -GrpSenders 10 -GrpReceivers 20
    } else {
        & (Join-Path $PSScriptRoot "prepare-e2e.ps1") -PvtSenders 50 -PvtReceivers 50 -GrpSenders 50 -GrpReceivers 200
    }
}

# === Phase 1: WebSocket 连接容量 ===
if (-not $Only -or $Only -eq "01") {
    if ($Quick) {
        runPlan -Plan "01-connection-capacity" -Threads 500 -Duration 120 -Ramp 30 `
            -Goal "连接容量 (500 连接, 2 分钟)"
    } else {
        runPlan -Plan "01-connection-capacity" -Threads 5000 -Duration 300 -Ramp 60 `
            -Goal "连接容量 (5000 连接, 5 分钟)"
    }
}

# === Phase 2: 私聊消息吞吐 ===
# 限速 send-rate-limit=200/s, 400 线程 x 25 msg/s (40ms延迟) = 10000 msg/s
if (-not $Only -or $Only -eq "02") {
    if ($Quick) {
        runPlan -Plan "02-private-throughput" -Threads 50 -Duration 60 -Ramp 10 `
            -Extra "-JdelayMs=100" `
            -Goal "私聊吞吐 (50 线程 x 10 msg/s = 500 msg/s)"
    } else {
        runPlan -Plan "02-private-throughput" -Threads 400 -Duration 180 -Ramp 30 `
            -Extra "-JdelayMs=40" `
            -Goal "私聊吞吐 (400 线程 x 25 msg/s = 10000 msg/s)"
    }
}

# === Phase 3: 群聊消息吞吐 ===
# 限速 group-rate-limit=100/s per user per group
# 注意: 5000 人大群扇出压力大, 200+ 线程可能被踢, 建议用 50-100 线程
if (-not $Only -or $Only -eq "03") {
    if ($Quick) {
        runPlan -Plan "03-group-throughput" -Threads 20 -Duration 60 -Ramp 10 `
            -Extra "-JdelayMs=100" `
            -Goal "群聊吞吐 (20 线程 x 10 msg/s = 200 msg/s)"
    } else {
        runPlan -Plan "03-group-throughput" -Threads 100 -Duration 180 -Ramp 30 `
            -Extra "-JdelayMs=40" `
            -Goal "群聊吞吐 (100 线程 x 25 msg/s = 2500 msg/s)"
    }
}

# === Phase 4: 私聊端到端投递 ===
# 50 发送者 x 5 msg/s (200ms延迟) = 250 msg/s, 50 接收者
if (-not $Only -or $Only -eq "04") {
    if ($Quick) {
        runPlan -Plan "04-private-e2e" -Threads 10 -Duration 60 -Ramp 5 `
            -Extra "-JsendThreads=10 -JrecvThreads=10 -JsendDelayMs=500 -JwarmupMs=10000 -JrecvDuration=75" `
            -Goal "私聊 E2E (10 发送者 + 10 接收者)"
    } else {
        runPlan -Plan "04-private-e2e" -Threads 100 -Duration 180 -Ramp 20 `
            -Extra "-JsendThreads=50 -JrecvThreads=50 -JsendDelayMs=200 -JwarmupMs=20000 -JrecvDuration=195 -JrecvReadTimeout=1000" `
            -Goal "私聊 E2E (50 发送者 + 50 接收者, 250 msg/s)"
    }
}

# === Phase 5: 群聊端到端投递 ===
# 50 发送者 x 5 msg/s (200ms延迟) = 250 msg/s, 200 接收者
# 群扇出: 250 msg/s x 200 接收者 = 50000 推送/s
if (-not $Only -or $Only -eq "05") {
    if ($Quick) {
        runPlan -Plan "05-group-e2e" -Threads 10 -Duration 60 -Ramp 5 `
            -Extra "-JsendThreads=10 -JrecvThreads=20 -JsendDelayMs=500 -JwarmupMs=10000 -JrecvDuration=75" `
            -Goal "群聊 E2E (10 发送者 + 20 接收者)"
    } else {
        runPlan -Plan "05-group-e2e" -Threads 250 -Duration 180 -Ramp 20 `
            -Extra "-JsendThreads=50 -JrecvThreads=200 -JsendDelayMs=200 -JwarmupMs=20000 -JrecvDuration=195 -JrecvReadTimeout=500" `
            -Goal "群聊 E2E (50 发送者 + 200 接收者, 250 msg/s, 扇出 50000/s)"
    }
}

# === Phase 6: 混合场景 ===
# 70% 私聊 + 30% 群聊, 总计 ~10000 msg/s
if (-not $Only -or $Only -eq "06") {
    if ($Quick) {
        runPlan -Plan "06-mixed-scenario" -Threads 50 -Duration 60 -Ramp 10 `
            -Extra "-JpvtThreads=35 -JgrpThreads=15 -JpvtDelayMs=100 -JgrpDelayMs=100" `
            -Goal "混合场景 (35 私聊 + 15 群聊)"
    } else {
        runPlan -Plan "06-mixed-scenario" -Threads 400 -Duration 180 -Ramp 30 `
            -Extra "-JpvtThreads=280 -JgrpThreads=120 -JpvtDelayMs=40 -JgrpDelayMs=40" `
            -Goal "混合场景 (280 私聊 + 120 群聊, ~10000 msg/s)"
    }
}

# === 汇总 ===
Write-Host ""
Write-Host ("=" * 70) -ForegroundColor Green
Write-Host "  全部完成. 汇总:" -ForegroundColor Green
Write-Host ("=" * 70) -ForegroundColor Green
$summary | Format-Table -AutoSize
Write-Host ""
Write-Host "HTML 报告在: $($root)\reports\" -ForegroundColor Cyan
Write-Host "JTL 结果在:  $($root)\results\" -ForegroundColor Cyan
