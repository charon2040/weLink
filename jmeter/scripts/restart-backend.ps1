# 轻量重启: 只重启 backend, 不清 DB/Redis, 让数据累积
# 用法: pwsh restart-backend.ps1

$ErrorActionPreference = "Stop"
$projectRoot = Resolve-Path (Join-Path $PSScriptRoot ".." "..")
$jarPath = Join-Path $projectRoot "target" "WeLink-0.0.1-SNAPSHOT.jar"

Write-Host "→ 停 backend java 进程" -ForegroundColor Yellow
$procs = Get-Process java -ErrorAction SilentlyContinue | Where-Object {
    try { $_.MainModule.FileName -like '*\java.exe*' -and (Get-Process -Id $_.Id).Path -notlike '*jmeter*' } catch { $false }
}
if ($procs) { $procs | Stop-Process -Force; Start-Sleep 3 }

Write-Host "→ 启 backend (后台, 日志 backend.log)" -ForegroundColor Yellow
$logFile = Join-Path $projectRoot "backend.log"
Start-Process -FilePath "java" -ArgumentList "-Xms4g","-Xmx6g","-XX:+UseG1GC","-jar",$jarPath `
    -RedirectStandardOutput $logFile -RedirectStandardError "$logFile.err" `
    -WindowStyle Hidden | Out-Null

Write-Host "→ 等 30 秒 healthy..." -ForegroundColor Yellow
for ($i = 1; $i -le 30; $i++) {
    Start-Sleep 1
    try {
        if ((Invoke-WebRequest http://localhost:8080/actuator/health -UseBasicParsing -TimeoutSec 1 2>$null).StatusCode -eq 200) {
            Write-Host "  ✓ backend healthy (启动 $i s)" -ForegroundColor Green
            exit 0
        }
    } catch {}
}
Write-Host "  ✗ 30 秒内没起来" -ForegroundColor Red
exit 1
