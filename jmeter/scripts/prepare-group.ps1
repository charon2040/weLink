# 建一个测试群 + 邀请前 N 个 token 用户进群
# 用法: pwsh prepare-group.ps1 [-Members 50] [-GroupName perf_group_50]
# 读: jmeter/data/tokens.csv
# 产出: jmeter/data/group-info.csv (groupId,groupNo,ownerToken,memberCsvPath)

param(
    [int]$Members = 50,
    [string]$GroupName = ""
)

$ErrorActionPreference = "Stop"
$dataDir = Join-Path $PSScriptRoot ".." "data"
$tokensCsv = Join-Path $dataDir "tokens.csv"
$groupInfoCsv = Join-Path $dataDir "group-info.csv"

if (-not (Test-Path $tokensCsv)) {
    Write-Host "缺少 $tokensCsv" -ForegroundColor Red
    exit 1
}

$tokens = Import-Csv $tokensCsv
if ($tokens.Count -lt ($Members + 1)) {
    Write-Host "tokens 池不够 $($Members + 1) 个" -ForegroundColor Red
    exit 1
}

if ([string]::IsNullOrEmpty($GroupName)) { $GroupName = "perf_group_$Members`_$(Get-Random -Maximum 9999)" }
$apiBase = "http://localhost:8080"
$owner = $tokens[0]

Write-Host "==== 创建 $Members-人 测试群: $GroupName ====" -ForegroundColor Cyan

$body = @{ groupName = $GroupName; notice = "Perf test group"; memberIds = @() } | ConvertTo-Json -Compress
$r = Invoke-WebRequest -Method POST "$apiBase/api/v1/group" -Headers @{Authorization = "Bearer $($owner.accessToken)"} -Body $body -ContentType 'application/json' -UseBasicParsing -TimeoutSec 5
$g = ($r.Content | ConvertFrom-Json).data
$gid = $g.id
$gno = $g.groupNo
Write-Host "群 id=$gid groupNo=$gno (群主=$($owner.username))"

# 批量邀请
$memberIds = @()
for ($i = 1; $i -le $Members; $i++) {
    $memberIds += $tokens[$i].userId
}
$inviteBody = $memberIds | ConvertTo-Json -Compress
if ($memberIds.Count -eq 1) { $inviteBody = "[$($memberIds[0])]" }  # PS ConvertTo-Json 单元素不带 []

$inviteRes = Invoke-WebRequest -Method POST "$apiBase/api/v1/group/$gid/invite" -Headers @{Authorization = "Bearer $($owner.accessToken)"} -Body $inviteBody -ContentType 'application/json' -UseBasicParsing -TimeoutSec 10
Write-Host "邀请响应: $($inviteRes.Content)"

# 写群成员 CSV (groupId,userId,token), 供消息发送 plan 使用
$membersCsv = Join-Path $dataDir "group-members.csv"
"groupId,userId,token" | Out-File -FilePath $membersCsv -Encoding utf8
for ($i = 0; $i -le $Members; $i++) {
    $u = $tokens[$i]
    "$gid,$($u.userId),$($u.accessToken)" | Add-Content -Path $membersCsv -Encoding utf8
}

# 写群信息 CSV
"groupId,groupNo,ownerToken" | Out-File -FilePath $groupInfoCsv -Encoding utf8
"$gid,$gno,$($owner.accessToken)" | Add-Content -Path $groupInfoCsv -Encoding utf8

Write-Host ""
Write-Host "==== 完成 ====" -ForegroundColor Green
Write-Host "群:       $GroupName ($gid)"
Write-Host "群号:     $gno"
Write-Host "成员数:   $($Members + 1) (含群主)"
Write-Host "成员 CSV: $membersCsv"
Write-Host "群信息:   $groupInfoCsv"
