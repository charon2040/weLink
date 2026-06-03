# 把 tokens.csv 中的用户两两配对生成私聊对, 用于 04-private-chat.jmx
# 用法: pwsh prepare-private-pairs.ps1 [-Pairs 100]
# 读: jmeter/data/tokens.csv
# 产出: jmeter/data/private-pairs.csv (aUsername,aUserId,aToken,bUsername,bUserId,bToken)

param(
    [int]$Pairs = 100
)

$ErrorActionPreference = "Stop"
$dataDir = Join-Path $PSScriptRoot ".." "data"
$tokensCsv = Join-Path $dataDir "tokens.csv"
$pairsCsv = Join-Path $dataDir "private-pairs.csv"

if (-not (Test-Path $tokensCsv)) {
    Write-Host "缺少 $tokensCsv, 先运行 prepare-tokens.ps1" -ForegroundColor Red
    exit 1
}

$tokens = Import-Csv $tokensCsv
if ($tokens.Count -lt ($Pairs * 2)) {
    Write-Host "tokens 池 $($tokens.Count) 不够配 $Pairs 对 (需 $($Pairs * 2))" -ForegroundColor Red
    exit 1
}

Write-Host "==== 配对 $Pairs 对私聊用户 (互加好友) ====" -ForegroundColor Cyan

"aUsername,aUserId,aToken,bUsername,bUserId,bToken" | Out-File -FilePath $pairsCsv -Encoding utf8

$apiBase = "http://localhost:8080"
$ok = 0; $fail = 0
for ($i = 0; $i -lt $Pairs; $i++) {
    $a = $tokens[$i * 2]
    $b = $tokens[$i * 2 + 1]
    try {
        # A 申请加 B
        $r1 = Invoke-WebRequest -Method POST "$apiBase/api/v1/friend/apply/$($b.userId)" -Headers @{Authorization = "Bearer $($a.accessToken)"} -UseBasicParsing -TimeoutSec 5
        # B 同意
        $r2 = Invoke-WebRequest -Method POST "$apiBase/api/v1/friend/accept/$($a.userId)" -Headers @{Authorization = "Bearer $($b.accessToken)"} -UseBasicParsing -TimeoutSec 5
        "$($a.username),$($a.userId),$($a.accessToken),$($b.username),$($b.userId),$($b.accessToken)" | Add-Content -Path $pairsCsv -Encoding utf8
        $ok++
    } catch {
        $fail++
    }
    if (($ok + $fail) % 20 -eq 0) {
        Write-Host "  进度: $($ok + $fail) / $Pairs" -ForegroundColor Gray
    }
}

Write-Host ""
Write-Host "==== 完成 ====" -ForegroundColor Green
Write-Host "成对: $ok"
Write-Host "失败: $fail"
Write-Host "CSV:  $pairsCsv"
