# WeLink IM 压测 & 投递可靠性测试 - 一键执行
# 用法: pwsh stress.ps1 [-Quick] [-Only <plan>]
#   -Quick   快速模式 (小规模, 验证流程)
#   -Only    只跑指定 plan (01-09)
#
# 正常模式目标: 10000 msg/s 私聊, 100 人单群稳定基线, 16x100 多群 E2E
# 前置: 服务端 rate-limit 已调大 (send=200/s, group=10/s)

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
    Write-Host "  目标: 私聊 10K msg/s, 100人单群约100 group msg/s, 16x100 多群 E2E" -ForegroundColor Yellow
}

# === 数据准备 ===
# E2E 数据必须每次重新生成 (token 2h 过期, 用户 ID 可能变化)
$e2eDataDir = Join-Path $root "data"
$needMultiGroup = -not (Test-Path (Join-Path $e2eDataDir "multi-group-members.csv"))

Write-Host ""
Write-Host "  重新生成 E2E 数据 (确保 token 有效) ..." -ForegroundColor Yellow
if ($Quick) {
    & (Join-Path $PSScriptRoot "prepare-e2e.ps1") -PvtSenders 10 -PvtReceivers 10 -GrpSenders 10 -GrpReceivers 20
} else {
    & (Join-Path $PSScriptRoot "prepare-e2e.ps1") -PvtSenders 500 -PvtReceivers 200 -GrpSenders 50 -GrpReceivers 100
}

if ($needMultiGroup) {
    Write-Host "  多群数据未就绪, 运行准备 ..." -ForegroundColor Yellow
    if ($Quick) {
        & (Join-Path $PSScriptRoot "prepare-multi-group.ps1") -GroupCount 5 -MembersPerGroup 20
    } else {
        & (Join-Path $PSScriptRoot "prepare-multi-group.ps1") -GroupCount 16 -MembersPerGroup 100
    }
}

# === Phase 1: WebSocket 连接容量 ===
if (-not $Only -or $Only -eq "01") {
    if ($Quick) {
        runPlan -Plan "01-connection-capacity" -Threads 500 -Duration 120 -Ramp 30 `
            -Goal "连接容量 (500 连接, 2 分钟)"
    } else {
        runPlan -Plan "01-connection-capacity" -Threads 5000 -Duration 90 -Ramp 30 `
            -Goal "连接容量 (5000 连接, 90 秒)"
    }
}

# === Phase 2: 私聊消息吞吐 (10000 msg/s) ===
if (-not $Only -or $Only -eq "02") {
    if ($Quick) {
        runPlan -Plan "02-private-throughput" -Threads 50 -Duration 60 -Ramp 10 `
            -Extra "-JdelayMs=100" `
            -Goal "私聊吞吐 (50 线程 x 10 msg/s = 500 msg/s)"
    } else {
        runPlan -Plan "02-private-throughput" -Threads 500 -Duration 90 -Ramp 20 `
            -Extra "-JdelayMs=40" `
            -Goal "私聊吞吐 (500 线程 x 25 msg/s = 12500 理论, 实测 10K+)"
    }
}

# === Phase 3: 群聊消息吞吐 - 100人单群稳定基线 ===
if (-not $Only -or $Only -eq "03") {
    if ($Quick) {
        runPlan -Plan "03-group-throughput" -Threads 20 -Duration 60 -Ramp 10 `
            -Extra "-JdelayMs=100" `
            -Goal "群聊吞吐 (20 线程 x 10 msg/s = 200 msg/s)"
    } else {
        runPlan -Plan "03-group-throughput" -Threads 50 -Duration 90 -Ramp 15 `
            -Extra "-JdelayMs=500" `
            -Goal "100人单群入口吞吐 (50 线程 x 2 msg/s = 100 group msg/s)"
    }
}

# === Phase 4: 私聊端到端投递 (当前 JMeter 口径 7K+ msg/s) ===
if (-not $Only -or $Only -eq "04") {
    if ($Quick) {
        runPlan -Plan "04-private-e2e" -Threads 20 -Duration 60 -Ramp 5 `
            -Extra "-JsendThreads=10 -JrecvThreads=10 -JsendDelayMs=500 -JwarmupMs=10000 -JrecvDuration=75" `
            -Goal "私聊 E2E (10 发送者 + 10 接收者)"
    } else {
        runPlan -Plan "04-private-e2e" -Threads 700 -Duration 90 -Ramp 20 `
            -Extra "-JsendThreads=500 -JrecvThreads=200 -JsendDelayMs=50 -JwarmupMs=10000 -JrecvDuration=120 -JdrainSec=30 -JrecvReadTimeout=3000" `
            -Goal "私聊 E2E (500 发送者 + 200 接收者, 当前 JMeter 口径 7K+ msg/s)"
    }
}

# === Phase 5: 群聊端到端投递 - 100人单群稳定基线 ===
if (-not $Only -or $Only -eq "05") {
    if ($Quick) {
        runPlan -Plan "05-group-e2e" -Threads 20 -Duration 60 -Ramp 5 `
            -Extra "-JsendThreads=10 -JrecvThreads=10 -JsendDelayMs=500 -JwarmupMs=10000 -JrecvDuration=75" `
            -Goal "群聊 E2E (10 发送者 + 10 接收者)"
    } else {
        runPlan -Plan "05-group-e2e" -Threads 150 -Duration 90 -Ramp 20 `
            -Extra "-JsendThreads=50 -JrecvThreads=100 -JsendDelayMs=500 -JwarmupMs=10000 -JrecvDuration=120 -JdrainSec=30 -JrecvReadTimeout=3000" `
            -Goal "100人单群 E2E (50 发送者 + 100 接收者, 约100 group msg/s)"
    }
}

# === Phase 6: 混合场景吞吐 (8000 msg/s) ===
if (-not $Only -or $Only -eq "06") {
    if ($Quick) {
        runPlan -Plan "06-mixed-scenario" -Threads 50 -Duration 60 -Ramp 10 `
            -Extra "-JpvtThreads=35 -JgrpThreads=15 -JpvtDelayMs=100 -JgrpDelayMs=100" `
            -Goal "混合场景 (35 私聊 + 15 群聊)"
    } else {
        runPlan -Plan "06-mixed-scenario" -Threads 350 -Duration 90 -Ramp 20 `
            -Extra "-JpvtThreads=300 -JgrpThreads=50 -JpvtDelayMs=50 -JgrpDelayMs=500" `
            -Goal "混合场景 (300 私聊约6000 msg/s + 50 群聊约100 group msg/s)"
    }
}

# === Phase 7: 多群聊入口吞吐 - 16x100 ===
if (-not $Only -or $Only -eq "07") {
    if ($Quick) {
        runPlan -Plan "07-multi-group-throughput" -Threads 15 -Duration 60 -Ramp 10 `
            -Extra "-JdelayMs=200" `
            -Goal "多群吞吐 (15 线程 x 5 msg/s = 75 msg/s)"
    } else {
        runPlan -Plan "07-multi-group-throughput" -Threads 160 -Duration 90 -Ramp 20 `
            -Extra "-JdelayMs=500" `
            -Goal "16x100 多群入口吞吐 (160 线程 x 2 msg/s = 320 group msg/s)"
    }
}

# === Phase 8: 混合 E2E 投递 ===
if (-not $Only -or $Only -eq "08") {
    if ($Quick) {
        runPlan -Plan "08-mixed-e2e" -Threads 40 -Duration 60 -Ramp 5 `
            -Extra "-JpvtSendThreads=10 -JpvtRecvThreads=10 -JgrpSendThreads=10 -JgrpRecvThreads=10 -JpvtSendDelayMs=500 -JgrpSendDelayMs=500 -JwarmupMs=10000 -JrecvDuration=75" `
            -Goal "混合 E2E (10 私聊发送+10接收, 10 群聊发送+10接收)"
    } else {
        runPlan -Plan "08-mixed-e2e" -Threads 550 -Duration 90 -Ramp 20 `
            -Extra "-JpvtSendThreads=300 -JpvtRecvThreads=150 -JgrpSendThreads=50 -JgrpRecvThreads=50 -JpvtSendDelayMs=50 -JgrpSendDelayMs=500 -JwarmupMs=10000 -JrecvDuration=120 -JdrainSec=30 -JrecvReadTimeout=3000" `
            -Goal "混合 E2E (私聊约6000 msg/s + 群聊约100 group msg/s)"
    }
}

# === Phase 9: 多群聊 E2E 投递 - 16x100 ===
if (-not $Only -or $Only -eq "09") {
    if ($Quick) {
        runPlan -Plan "09-multi-group-e2e" -Threads 30 -Duration 60 -Ramp 5 `
            -Extra "-JsendThreads=10 -JrecvThreads=20 -JsendDelayMs=500 -JwarmupMs=10000 -JrecvDuration=75" `
            -Goal "多群 E2E (10 发送者 + 20 接收者)"
    } else {
        runPlan -Plan "09-multi-group-e2e" -Threads 960 -Duration 90 -Ramp 20 `
            -Extra "-JsendThreads=160 -JrecvThreads=800 -JsendDelayMs=500 -JwarmupMs=10000 -JrecvDuration=120 -JdrainSec=30 -JsendRamp=20 -JrecvRamp=20 -JrecvReadTimeout=3000" `
            -Goal "16x100 多群 E2E (160 发送者 + 800 接收者, 约320 group msg/s 入口目标)"
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
