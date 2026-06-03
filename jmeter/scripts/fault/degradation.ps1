# 切换降级等级, 用于在压测过程中观察 L1~L5 各级效果
# 用法: pwsh degradation.ps1 -Level 3

param(
    [Parameter(Mandatory = $true)] [int]$Level
)

$h = @{ "X-Internal-Secret" = "welink-internal-default-secret-change-me" }
$r = Invoke-WebRequest -Method POST "http://localhost:8080/admin/degradation/level/$Level" -Headers $h -UseBasicParsing
Write-Host $r.Content
