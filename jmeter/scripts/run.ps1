# 跑指定 JMeter plan + 自动产 HTML 报告
# 用法:
#   pwsh run.ps1 -Plan 01-register -Threads 50 -Duration 60
#   pwsh run.ps1 -Plan 03-history-fetch -Threads 200 -Duration 60
#   pwsh run.ps1 -Plan 05-ws-longlived -Threads 500 -Duration 1800
# 参数:
#   -Plan        必填, plans 目录下文件名(不含 .jmx)
#   -Threads     线程数 (传给 -Jthreads)
#   -Duration    持续秒数 (传给 -Jduration)
#   -Ramp        ramp-up 秒数 (传给 -Jramp)
#   -ExtraArgs   其他 jmeter -J 参数, 例如 "-Jrate=100 -Jhost=localhost"

param(
    [Parameter(Mandatory = $true)] [string]$Plan,
    [int]$Threads = 0,
    [int]$Duration = 0,
    [int]$Ramp = 0,
    [string]$ExtraArgs = ""
)

$ErrorActionPreference = "Stop"
$root = Resolve-Path (Join-Path $PSScriptRoot "..")
$projectRoot = Resolve-Path (Join-Path $root "..")
$jmeterCandidates = @(
    (Join-Path $projectRoot "apache-jmeter-5.6.3"),
    (Join-Path $projectRoot "..\apache-jmeter-5.6.3")
)
$jmeterHome = $null
foreach ($candidate in $jmeterCandidates) {
    if (Test-Path $candidate) {
        $jmeterHome = Resolve-Path $candidate
        break
    }
}
if (-not $jmeterHome) {
    throw "未找到 apache-jmeter-5.6.3，请检查仓库目录或安装路径。"
}
$jmeterBin = Join-Path $jmeterHome "bin" "jmeter.bat"
$planFile = Join-Path $root "plans" "$Plan.jmx"

function Ensure-JMeterEnvironment {
    $system32 = Join-Path $env:WINDIR "System32"
    if ($env:Path -notlike "*$system32*") {
        $env:Path = "$system32;$env:Path"
    }

    $javaExe = $null
    if ($env:JAVA_HOME) {
        $candidate = Join-Path $env:JAVA_HOME "bin\java.exe"
        if (Test-Path $candidate) {
            $javaExe = $candidate
        }
    }

    if (-not $javaExe) {
        $candidatePatterns = @(
            (Join-Path $env:USERPROFILE ".vscode\extensions\redhat.java-*\jre\*\bin\java.exe"),
            (Join-Path $env:USERPROFILE ".trae\extensions\redhat.java-*\jre\*\bin\java.exe"),
            (Join-Path $env:USERPROFILE ".cursor\extensions\redhat.java-*\jre\*\bin\java.exe"),
            "C:\Program Files\Common Files\Oracle\Java\javapath\java.exe",
            "C:\Program Files\Common Files\Oracle\Java\javapath_target_28482703\java.exe"
        )
        foreach ($pattern in $candidatePatterns) {
            $matches = @(Get-ChildItem $pattern -ErrorAction SilentlyContinue | Sort-Object FullName -Descending)
            if ($matches.Count -gt 0) {
                $javaExe = $matches[0].FullName
                break
            }
        }
    }

    if (-not $javaExe) {
        throw "未找到可用的 java.exe，请先设置 JAVA_HOME。"
    }

    $javaBin = Split-Path $javaExe -Parent
    $env:JAVA_HOME = Split-Path $javaBin -Parent
    if ($env:Path -notlike "*$javaBin*") {
        $env:Path = "$javaBin;$env:Path"
    }
}

Ensure-JMeterEnvironment

if (-not (Test-Path $planFile)) {
    Write-Host "找不到 plan: $planFile" -ForegroundColor Red
    Write-Host "可用 plans:" -ForegroundColor Yellow
    Get-ChildItem (Join-Path $root "plans") -Filter "*.jmx" | ForEach-Object { Write-Host "  $($_.BaseName)" }
    exit 1
}

$stamp = Get-Date -Format "yyyyMMdd_HHmmss"
$resultsDir = Join-Path $root "results"
$reportsDir = Join-Path $root "reports"
$resultFile = Join-Path $resultsDir "$Plan`_$stamp.jtl"
$reportDir = Join-Path $reportsDir "$Plan`_$stamp"
$logFile = Join-Path $resultsDir "$Plan`_$stamp.log"
$dataDir = Join-Path $root "data"

$jmArgs = @("-n", "-t", $planFile, "-l", $resultFile, "-e", "-o", $reportDir, "-j", $logFile,
           "-Jdata.dir=$dataDir")
if ($Threads -gt 0) { $jmArgs += "-Jthreads=$Threads" }
if ($Duration -gt 0) { $jmArgs += "-Jduration=$Duration" }
if ($Ramp -gt 0) { $jmArgs += "-Jramp=$Ramp" }
if ($ExtraArgs) { $jmArgs += ($ExtraArgs -split ' ') }

Write-Host "==== 运行 $Plan ====" -ForegroundColor Cyan
Write-Host "Plan:     $planFile"
Write-Host "Threads:  $Threads"
Write-Host "Duration: $Duration s"
Write-Host "Result:   $resultFile"
Write-Host "Report:   $reportDir/index.html"
Write-Host ""

& $jmeterBin @jmArgs

if (Test-Path (Join-Path $reportDir "index.html")) {
    Write-Host ""
    Write-Host "==== 完成 ====" -ForegroundColor Green
    Write-Host "HTML 报告: $reportDir\index.html" -ForegroundColor Green
    Write-Host "JTL 文件:  $resultFile"
} else {
    Write-Host ""
    Write-Host "==== 报告生成失败, 看 $logFile ====" -ForegroundColor Red
}
