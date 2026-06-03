# 一键准备所有压测数据 (DB 直插模式, 秒级完成)
# 用法: pwsh prepare-all.ps1 [-Users 25000] [-Pairs 5000] [-Members 150] [-MultiGroupCount 16] [-MultiGroupMembers 100]
#
# 默认 2.5 万账号 / 5000 对私聊 / 150 人群 / 16 多群 x 100 人
# 适合当前回归压测 (10K msg/s 私聊, 100 人单群稳定基线, 16x100 多群 E2E)
# 注意: 每次运行都会重新签发 token 和 E2E CSV, 避免旧 token 过期问题

param(
    [int]$Users = 25000,
    [int]$Pairs = 5000,
    [int]$Members = 150,
    [int]$MultiGroupCount = 16,
    [int]$MultiGroupMembers = 100
)

$ErrorActionPreference = "Stop"
$here = $PSScriptRoot
$t0 = Get-Date

Write-Host ""
Write-Host ("=" * 70) -ForegroundColor Cyan
Write-Host "  一键准备压测数据" -ForegroundColor Cyan
Write-Host "  $Users 账号 / $Pairs 对私聊 / $Members 人群 / $MultiGroupCount 多群 x $MultiGroupMembers" -ForegroundColor Cyan
Write-Host ("=" * 70) -ForegroundColor Cyan

& (Join-Path $here "prepare-db-bulk.ps1") -Count $Users
if ($LASTEXITCODE -ne 0) { Write-Host "bulk 失败"; exit 1 }

Write-Host ""
& (Join-Path $here "prepare-db-pairs.ps1") -Pairs $Pairs
if ($LASTEXITCODE -ne 0) { Write-Host "pairs 失败"; exit 1 }

Write-Host ""
& (Join-Path $here "prepare-db-group.ps1") -Members $Members
if ($LASTEXITCODE -ne 0) { Write-Host "group 失败"; exit 1 }

Write-Host ""
& (Join-Path $here "prepare-multi-group.ps1") -GroupCount $MultiGroupCount -MembersPerGroup $MultiGroupMembers
if ($LASTEXITCODE -ne 0) { Write-Host "multi-group 失败"; exit 1 }

Write-Host ""
& (Join-Path $here "prepare-e2e.ps1") -PvtSenders 500 -PvtReceivers 200 -GrpSenders 50 -GrpReceivers 100
if ($LASTEXITCODE -ne 0) { Write-Host "e2e 失败"; exit 1 }

$elapsed = ((Get-Date) - $t0).TotalSeconds
Write-Host ""
Write-Host ("=" * 70) -ForegroundColor Green
Write-Host "  全部完成. 总耗时 $('{0:F1}' -f $elapsed) 秒" -ForegroundColor Green
Write-Host ("=" * 70) -ForegroundColor Green
Write-Host ""
Write-Host "现在可以跑压测了:" -ForegroundColor Cyan
Write-Host "  pwsh stress.ps1               # 一键全套 (并发 + E2E)"
Write-Host "  pwsh stress.ps1 -Quick        # 快速验证"
Write-Host "  pwsh stress.ps1 -Only 01      # 只跑WS连接容量"
Write-Host "  pwsh stress.ps1 -Only 04      # 只跑私聊 E2E"
Write-Host "  pwsh stress.ps1 -Only 07      # 只跑多群吞吐"
Write-Host "  pwsh stress.ps1 -Only 09      # 只跑多群 E2E"
