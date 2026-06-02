# 批量登录拿 token, 输出 token 池供后续压测 plan 使用
# 用法: pwsh prepare-tokens.ps1 [-Limit 1000]
# 读: jmeter/data/usernames.csv
# 产出: jmeter/data/tokens.csv (username,userId,accessToken)

param(
    [int]$Limit = 0,  # 0 = all
    [string]$ApiBase = "http://localhost:8080"
)

$ErrorActionPreference = "Stop"
$dataDir = Join-Path $PSScriptRoot ".." "data"
$usersCsv = Join-Path $dataDir "usernames.csv"
$tokensCsv = Join-Path $dataDir "tokens.csv"

if (-not (Test-Path $usersCsv)) {
    Write-Host "缺少 $usersCsv, 先运行 prepare-users.ps1" -ForegroundColor Red
    exit 1
}

$users = Import-Csv $usersCsv
if ($Limit -gt 0 -and $users.Count -gt $Limit) {
    $users = $users[0..($Limit - 1)]
}

Write-Host "==== 批量登录 $($users.Count) 个账号 ====" -ForegroundColor Cyan

"username,userId,accessToken" | Out-File -FilePath $tokensCsv -Encoding utf8

$ok = 0; $fail = 0
foreach ($u in $users) {
    $body = @{ username = $u.username; password = $u.password } | ConvertTo-Json -Compress
    try {
        $r = Invoke-WebRequest -Method POST "$ApiBase/api/v1/auth/login" -Body $body -ContentType 'application/json' -UseBasicParsing -TimeoutSec 5
        $json = $r.Content | ConvertFrom-Json
        if ($json.code -eq 200 -and $json.data.accessToken) {
            $uid = $json.data.userInfo.id
            $tok = $json.data.accessToken
            "$($u.username),$uid,$tok" | Add-Content -Path $tokensCsv -Encoding utf8
            $ok++
        } else {
            $fail++
        }
    } catch {
        $fail++
    }
    if (($ok + $fail) % 100 -eq 0) {
        Write-Host "  进度: $($ok + $fail) / $($users.Count)" -ForegroundColor Gray
    }
}

Write-Host ""
Write-Host "==== 完成 ====" -ForegroundColor Green
Write-Host "成功: $ok"
Write-Host "失败: $fail"
Write-Host "CSV:  $tokensCsv"
