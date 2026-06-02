# 准备端到端投递可靠性测试数据 (私聊 + 群聊)
# 用法: pwsh prepare-e2e.ps1 [-PvtSenders 10] [-PvtReceivers 10] [-GrpSenders 10] [-GrpReceivers 20]
# 前置: 先跑 prepare-all.ps1 生成 tokens.csv + private-pairs.csv + group-members.csv

param(
    [int]$PvtSenders = 10,
    [int]$PvtReceivers = 10,
    [int]$GrpSenders = 10,
    [int]$GrpReceivers = 20,
    [string]$JwtSecret = "WeLinkJwtSecretKey2026VeryLongSecretKeyForSecurity",
    [int]$AccessTokenSeconds = 7200
)

$ErrorActionPreference = "Stop"
$dataDir = Join-Path $PSScriptRoot ".." "data"
$tokensCsv = Join-Path $dataDir "tokens.csv"
$pairsCsv = Join-Path $dataDir "private-pairs.csv"
$membersCsv = Join-Path $dataDir "group-members.csv"

function Encode-Base64Url($bytes) {
    return [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

function New-Jwt {
    param([long]$UserId, [string]$Username, [string]$Secret, [int]$ExpSeconds = 7200)
    $headerJson = '{"alg":"HS384"}'
    $now = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    $payloadJson = "{`"sub`":`"$Username`",`"userId`":$UserId,`"iat`":$now,`"exp`":$($now + $ExpSeconds)}"
    $h = Encode-Base64Url ([Text.Encoding]::UTF8.GetBytes($headerJson))
    $p = Encode-Base64Url ([Text.Encoding]::UTF8.GetBytes($payloadJson))
    $hmac = New-Object System.Security.Cryptography.HMACSHA384
    $hmac.Key = [Text.Encoding]::UTF8.GetBytes($Secret)
    $sig = $hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes("$h.$p"))
    $s = Encode-Base64Url $sig
    return "$h.$p.$s"
}

$tokenMap = @{}
if (Test-Path $tokensCsv) {
    $tokens = Import-Csv $tokensCsv
    foreach ($t in $tokens) {
        $tokenMap[$t.userId] = $t.accessToken
    }
    Write-Host "读入 $($tokenMap.Count) 个已有 token" -ForegroundColor Gray
}

Write-Host ""
Write-Host ("=" * 70) -ForegroundColor Cyan
Write-Host "  准备 E2E 投递可靠性测试数据" -ForegroundColor Cyan
Write-Host ("=" * 70) -ForegroundColor Cyan

# === 私聊 E2E 数据 ===
Write-Host ""
Write-Host "---- 私聊 E2E ----" -ForegroundColor Yellow

if (-not (Test-Path $pairsCsv)) {
    Write-Host "缺少 $pairsCsv, 先运行 prepare-all.ps1" -ForegroundColor Red
    exit 1
}

$pairs = Import-Csv $pairsCsv
$totalPvtNeeded = $PvtSenders + $PvtReceivers
if ($pairs.Count -lt $PvtSenders) {
    Write-Host "private-pairs.csv 只有 $($pairs.Count) 行, 需要 $PvtSenders 对" -ForegroundColor Red
    exit 1
}

$pvtSendersCsv = Join-Path $dataDir "e2e-private-senders.csv"
$pvtReceiversCsv = Join-Path $dataDir "e2e-private-receivers.csv"

"senderId,receiverId,token" | Out-File -FilePath $pvtSendersCsv -Encoding utf8
for ($i = 0; $i -lt $PvtSenders; $i++) {
    $p = $pairs[$i]
    $token = $p.aToken
    if (-not $token) {
        $token = New-Jwt -UserId ([long]$p.aUserId) -Username $p.aUsername -Secret $JwtSecret -ExpSeconds $AccessTokenSeconds
    }
    "$($p.aUserId),$($p.bUserId),$token" | Add-Content -Path $pvtSendersCsv -Encoding utf8
}

"receiverId,token" | Out-File -FilePath $pvtReceiversCsv -Encoding utf8
for ($i = 0; $i -lt $PvtReceivers; $i++) {
    $p = $pairs[$i]
    $token = $p.bToken
    if (-not $token) {
        $token = New-Jwt -UserId ([long]$p.bUserId) -Username $p.bUsername -Secret $JwtSecret -ExpSeconds $AccessTokenSeconds
    }
    "$($p.bUserId),$token" | Add-Content -Path $pvtReceiversCsv -Encoding utf8
}

Write-Host "  私聊发送者:  $PvtSenders -> $pvtSendersCsv" -ForegroundColor Green
Write-Host "  私聊接收者:  $PvtReceivers -> $pvtReceiversCsv" -ForegroundColor Green

# === 群聊 E2E 数据 ===
Write-Host ""
Write-Host "---- 群聊 E2E ----" -ForegroundColor Yellow

if (-not (Test-Path $membersCsv)) {
    Write-Host "缺少 $membersCsv, 先运行 prepare-all.ps1" -ForegroundColor Red
    exit 1
}

$members = Import-Csv $membersCsv
$totalGrpNeeded = $GrpSenders + $GrpReceivers
if ($members.Count -lt $totalGrpNeeded) {
    Write-Host "group-members.csv 只有 $($members.Count) 行, 需要 $totalGrpNeeded" -ForegroundColor Red
    exit 1
}

$grpSendersCsv = Join-Path $dataDir "e2e-group-senders.csv"
$grpReceiversCsv = Join-Path $dataDir "e2e-group-receivers.csv"

"groupId,userId,token" | Out-File -FilePath $grpSendersCsv -Encoding utf8
for ($i = 0; $i -lt $GrpSenders; $i++) {
    $m = $members[$i]
    $token = $tokenMap[$m.userId]
    if (-not $token) {
        $token = New-Jwt -UserId ([long]$m.userId) -Username "perf_user_$($m.userId)" -Secret $JwtSecret -ExpSeconds $AccessTokenSeconds
    }
    "$($m.groupId),$($m.userId),$token" | Add-Content -Path $grpSendersCsv -Encoding utf8
}

"groupId,userId,token" | Out-File -FilePath $grpReceiversCsv -Encoding utf8
for ($i = $GrpSenders; $i -lt $GrpSenders + $GrpReceivers; $i++) {
    $m = $members[$i]
    $token = $tokenMap[$m.userId]
    if (-not $token) {
        $token = New-Jwt -UserId ([long]$m.userId) -Username "perf_user_$($m.userId)" -Secret $JwtSecret -ExpSeconds $AccessTokenSeconds
    }
    "$($m.groupId),$($m.userId),$token" | Add-Content -Path $grpReceiversCsv -Encoding utf8
}

Write-Host "  群聊发送者:  $GrpSenders -> $grpSendersCsv" -ForegroundColor Green
Write-Host "  群聊接收者:  $GrpReceivers -> $grpReceiversCsv" -ForegroundColor Green

Write-Host ""
Write-Host ("=" * 70) -ForegroundColor Green
Write-Host "  E2E 数据准备完成" -ForegroundColor Green
Write-Host ("=" * 70) -ForegroundColor Green
Write-Host ""
Write-Host "运行测试:" -ForegroundColor Cyan
Write-Host "  pwsh run.ps1 -Plan 04-private-e2e -Threads 10 -Duration 120 -ExtraArgs `"-JsendThreads=10 -JrecvThreads=10 -JsendDelayMs=200 -JwarmupMs=15000 -JrecvDuration=135`""
Write-Host "  pwsh run.ps1 -Plan 05-group-e2e -Threads 10 -Duration 120 -ExtraArgs `"-JsendThreads=10 -JrecvThreads=20 -JsendDelayMs=500 -JwarmupMs=15000 -JrecvDuration=135`""
