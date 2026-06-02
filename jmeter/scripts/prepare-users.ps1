# 批量注册压测账号
# 用法: pwsh prepare-users.ps1 -Count 1000 [-Prefix perf_]
# 产出: jmeter/data/usernames.csv (username,password)

param(
    [int]$Count = 1000,
    [string]$Prefix = "perf_",
    [string]$Password = "perf123456",
    [string]$ApiBase = "http://localhost:8080"
)

$ErrorActionPreference = "Stop"
$dataDir = Join-Path $PSScriptRoot ".." "data"
if (-not (Test-Path $dataDir)) { New-Item -ItemType Directory $dataDir | Out-Null }
$csvPath = Join-Path $dataDir "usernames.csv"

Write-Host "==== 批量注册 $Count 个账号 ====" -ForegroundColor Cyan
Write-Host "前缀:    $Prefix"
Write-Host "密码:    $Password"
Write-Host "API:     $ApiBase"
Write-Host "输出:    $csvPath"

# 写表头
"username,password" | Out-File -FilePath $csvPath -Encoding utf8

$ok = 0; $skip = 0; $fail = 0
$batch = 50  # 并发批
$tasks = @()

for ($i = 1; $i -le $Count; $i++) {
    $username = "$Prefix$i"
    $body = @{ username = $username; password = $Password; nickname = "Perf$i" } | ConvertTo-Json -Compress
    try {
        $r = Invoke-WebRequest -Method POST "$ApiBase/api/v1/auth/register" -Body $body -ContentType 'application/json' -UseBasicParsing -TimeoutSec 5
        $json = $r.Content | ConvertFrom-Json
        if ($json.code -eq 200) {
            "$username,$Password" | Add-Content -Path $csvPath -Encoding utf8
            $ok++
        } elseif ($json.code -eq 1002) {
            # 已存在 — 也写进 CSV (后续登录能用)
            "$username,$Password" | Add-Content -Path $csvPath -Encoding utf8
            $skip++
        } else {
            $fail++
            Write-Host "  ! $username 失败 code=$($json.code) $($json.message)" -ForegroundColor Yellow
        }
    } catch {
        $fail++
        Write-Host "  ! $username 异常 $($_.Exception.Message)" -ForegroundColor Yellow
    }
    if ($i % 100 -eq 0) {
        Write-Host "  进度: $i / $Count (ok=$ok skip=$skip fail=$fail)" -ForegroundColor Gray
    }
}

Write-Host ""
Write-Host "==== 完成 ====" -ForegroundColor Green
Write-Host "新注册: $ok"
Write-Host "已存在: $skip"
Write-Host "失败:   $fail"
Write-Host "CSV:    $csvPath"
