# 一键准备所有压测数据 (DB 直插模式, 秒级完成)
# 用法: pwsh prepare-all.ps1 [-Users 20000] [-Pairs 5000] [-Members 5000]
#
# 默认 2 万账号 / 5000 对私聊 / 5000 人群
# 适合冲量压测 (5K msg/s, 5K 长连接, 等)

param(
    [int]$Users = 20000,
    [int]$Pairs = 5000,
    [int]$Members = 5000
)

$ErrorActionPreference = "Stop"
$here = $PSScriptRoot
$t0 = Get-Date

Write-Host ""
Write-Host ("=" * 70) -ForegroundColor Cyan
Write-Host "  一键准备压测数据" -ForegroundColor Cyan
Write-Host "  $Users 账号 / $Pairs 对私聊 / $Members 人群" -ForegroundColor Cyan
Write-Host ("=" * 70) -ForegroundColor Cyan

& (Join-Path $here "prepare-db-bulk.ps1") -Count $Users
if ($LASTEXITCODE -ne 0) { Write-Host "bulk 失败"; exit 1 }

Write-Host ""
& (Join-Path $here "prepare-db-pairs.ps1") -Pairs $Pairs
if ($LASTEXITCODE -ne 0) { Write-Host "pairs 失败"; exit 1 }

Write-Host ""
& (Join-Path $here "prepare-db-group.ps1") -Members $Members
if ($LASTEXITCODE -ne 0) { Write-Host "group 失败"; exit 1 }

$elapsed = ((Get-Date) - $t0).TotalSeconds
Write-Host ""
Write-Host ("=" * 70) -ForegroundColor Green
Write-Host "  全部完成. 总耗时 $('{0:F1}' -f $elapsed) 秒" -ForegroundColor Green
Write-Host ("=" * 70) -ForegroundColor Green
Write-Host ""
Write-Host "现在可以跑压测了:" -ForegroundColor Cyan
Write-Host "  pwsh stress.ps1               # 一键全套 (并发 + E2E)"
Write-Host "  pwsh stress.ps1 -Quick        # 快速验证"
Write-Host "  pwsh stress.ps1 -Only 04      # 只跑私聊 E2E"
Write-Host "  pwsh prepare-e2e.ps1          # 单独准备 E2E 数据"
