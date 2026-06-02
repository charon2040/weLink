param(
    [string]$ResultsDir = (Join-Path $PSScriptRoot ".." "results"),
    [string]$OutputFile = (Join-Path $PSScriptRoot ".." "results" "performance-report.md"),
    [switch]$LatestOnly
)

$ErrorActionPreference = "Continue"

function Get-Stats {
    param([System.Collections.Generic.List[int]]$Values)
    if ($Values.Count -eq 0) { return $null }
    $sorted = $Values.ToArray() | Sort-Object
    $n = $sorted.Count
    $avg = [Math]::Round(($Values | Measure-Object -Average).Average, 1)
    return @{
        Count = $n
        Avg   = $avg
        Min   = $sorted[0]
        P50   = $sorted[[int]([Math]::Floor(($n-1)*0.50))]
        P90   = $sorted[[int]([Math]::Floor(($n-1)*0.90))]
        P95   = $sorted[[int]([Math]::Floor(($n-1)*0.95))]
        P99   = $sorted[[int]([Math]::Floor(($n-1)*0.99))]
        Max   = $sorted[-1]
    }
}

$jtlFiles = Get-ChildItem (Join-Path $ResultsDir "*.jtl") | Sort-Object Name

$latestByPlan = @{}
foreach ($f in $jtlFiles) {
    $planName = [IO.Path]::GetFileNameWithoutExtension($f.Name) -replace '_\d{8}_\d{6}$',''
    if (-not $latestByPlan[$planName] -or $f.LastWriteTime -gt $latestByPlan[$planName].LastWriteTime) {
        $latestByPlan[$planName] = $f
    }
}

$sb = [System.Text.StringBuilder]::new()

[void]$sb.AppendLine("# WeLink IM Performance Report")
[void]$sb.AppendLine("")
[void]$sb.AppendLine("Generated: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')")
[void]$sb.AppendLine("")

[void]$sb.AppendLine("## 1. Overview")
[void]$sb.AppendLine("")
[void]$sb.AppendLine("| Test Plan | Duration | Total Requests | Errors | Error Rate | Throughput |")
[void]$sb.AppendLine("|-----------|----------|----------------|--------|------------|------------|")

$allStats = @{}

foreach ($kv in $latestByPlan.GetEnumerator() | Sort-Object Name) {
    $f = $kv.Value
    Write-Host "  Parsing $($kv.Key)..." -ForegroundColor Gray

    $byLabel = @{}
    $byLabelOk = @{}
    $byLabelFail = @{}
    $totalReqs = 0; $totalErrors = 0
    $tsMin = [long]::MaxValue; $tsMax = [long]::MinValue

    $reader = [System.IO.StreamReader]::new($f.FullName)
    $reader.ReadLine() | Out-Null
    while ($null -ne ($line = $reader.ReadLine())) {
        $parts = $line -split ','
        if ($parts.Length -lt 9) { continue }
        $ts = [long]$parts[0]; $elapsed = [int]$parts[1]; $label = $parts[2]; $success = $parts[7] -eq 'true'
        if ($ts -lt $tsMin) { $tsMin = $ts }; if ($ts -gt $tsMax) { $tsMax = $ts }
        $totalReqs++
        if (-not $success) { $totalErrors++ }
        if (-not $byLabel.ContainsKey($label)) { $byLabel[$label] = [System.Collections.Generic.List[int]]::new(); $byLabelOk[$label] = [System.Collections.Generic.List[int]]::new(); $byLabelFail[$label] = [System.Collections.Generic.List[int]]::new() }
        $byLabel[$label].Add($elapsed)
        if ($success) { $byLabelOk[$label].Add($elapsed) } else { $byLabelFail[$label].Add($elapsed) }
    }
    $reader.Close()

    $durSec = if ($tsMax -gt $tsMin) { [Math]::Round(($tsMax - $tsMin) / 1000.0, 1) } else { 0 }
    $tput = if ($durSec -gt 0) { [Math]::Round($totalReqs / $durSec, 1) } else { 0 }
    $errRate = if ($totalReqs -gt 0) { [Math]::Round($totalErrors / $totalReqs * 100, 2) } else { 0 }

    [void]$sb.AppendLine("| $($kv.Key) | ${durSec}s | $totalReqs | $totalErrors | $errRate% | ${tput}/s |")

    $allStats[$kv.Key] = @{ ByLabel=$byLabel; ByLabelOk=$byLabelOk; ByLabelFail=$byLabelFail }
}

[void]$sb.AppendLine("")
[void]$sb.AppendLine("---")
[void]$sb.AppendLine("")

$connectLabels = @('WS Connect','Pvt WS Connect','Grp WS Connect','Sender Connect','Receiver Connect')
$authLabels = @('Auth Resp','Pvt Auth Resp','Grp Auth Resp','Sender Auth Resp','Receiver Auth Resp')
$sendLabels = @('Send Private Msg','Send Group Msg')
$recvLabels = @('Recv Private Msg','Recv Group Msg')

function Write-LatencySection {
    param([string]$Title, [string]$Desc, [string[]]$Labels)
    [void]$sb.AppendLine("### $Title")
    [void]$sb.AppendLine("")
    if ($Desc) { [void]$sb.AppendLine("$Desc"); [void]$sb.AppendLine("") }
    [void]$sb.AppendLine("| Plan | Label | Count | Avg(ms) | Min | P50 | P90 | P95 | P99 | Max |")
    [void]$sb.AppendLine("|------|-------|-------|---------|-----|-----|-----|-----|-----|-----|")
    foreach ($kv in $allStats.GetEnumerator() | Sort-Object Name) {
        $data = $kv.Value
        foreach ($label in $Labels) {
            if ($data.ByLabelOk.ContainsKey($label) -and $data.ByLabelOk[$label].Count -ge 5) {
                $s = Get-Stats $data.ByLabelOk[$label]
                if ($s) { [void]$sb.AppendLine("| $($kv.Key) | $label | $($s.Count) | $($s.Avg) | $($s.Min) | $($s.P50) | $($s.P90) | $($s.P95) | $($s.P99) | $($s.Max) |") }
            }
        }
    }
    [void]$sb.AppendLine("")
}

[void]$sb.AppendLine("## 2. High Concurrency - Latency Percentiles")
[void]$sb.AppendLine("")
Write-LatencySection "2.1 WebSocket Connection" "Time to establish WebSocket (TCP + WS handshake)." $connectLabels
Write-LatencySection "2.2 Auth Response" "Time from sending auth to receiving success response (includes JWT validation + Redis route write)." $authLabels
Write-LatencySection "2.3 Message Send (Write to WS)" "Time to write a message frame to the WebSocket. Fire-and-forget, does NOT include server ack." $sendLabels
Write-LatencySection "2.4 E2E Delivery (Receiver Read)" "Time from receiver issuing a WebSocket read to receiving the pushed message. Includes: Kafka transit + consumer processing + WS push." $recvLabels

[void]$sb.AppendLine("---")
[void]$sb.AppendLine("")
[void]$sb.AppendLine("## 3. Message Consistency & Delivery Reliability")
[void]$sb.AppendLine("")

$e2eReports = @(
    @{ Plan="04-private-e2e"; File="04-private-e2e-report.txt"; Title="Private Chat E2E" },
    @{ Plan="05-group-e2e"; File="05-group-e2e-report.txt"; Title="Group Chat E2E" }
)

[void]$sb.AppendLine("### 3.1 Delivery Rate")
[void]$sb.AppendLine("")
[void]$sb.AppendLine("| Test | Sent | Received | Delivery Rate | Recv Timeouts |")
[void]$sb.AppendLine("|------|------|----------|---------------|---------------|")

foreach ($e2e in $e2eReports) {
    $rp = Join-Path $ResultsDir $e2e.File
    if (Test-Path $rp) {
        $c = Get-Content $rp -Raw
        $sm = [regex]::Match($c, 'Messages Sent:\s*(\d+)'); $rm = [regex]::Match($c, 'Messages Received:\s*(\d+)')
        $em = [regex]::Match($c, 'Recv Timeouts:\s*(\d+)'); $dm = [regex]::Match($c, 'Delivery Rate:\s*([\d.]+)%')
        if ($sm.Success -and $rm.Success) {
            $sent = $sm.Groups[1].Value; $recv = $rm.Groups[1].Value
            $errs = if ($em.Success) { $em.Groups[1].Value } else { "0" }
            $rate = if ($dm.Success) { "$($dm.Groups[1].Value)%" } else { "$([Math]::Round([int]$recv/[int]$sent*100,2))%" }
            [void]$sb.AppendLine("| $($e2e.Title) | $sent | $recv | $rate | $errs |")
        }
    } else {
        [void]$sb.AppendLine("| $($e2e.Title) | - | - | - | - |")
    }
}

[void]$sb.AppendLine("")
[void]$sb.AppendLine("### 3.2 E2E Delivery Latency Distribution")
[void]$sb.AppendLine("")

foreach ($kv in $allStats.GetEnumerator() | Sort-Object Name) {
    $data = $kv.Value
    foreach ($label in $recvLabels) {
        if ($data.ByLabelOk.ContainsKey($label) -and $data.ByLabelOk[$label].Count -ge 100) {
            $s = Get-Stats $data.ByLabelOk[$label]
            if (-not $s) { continue }
            $vals = $data.ByLabelOk[$label].ToArray() | Sort-Object
            $n = $vals.Count
            $buckets = @(0,0,0,0,0,0,0,0)
            foreach ($v in $vals) {
                if     ($v -le 10)  { $buckets[0]++ }
                elseif ($v -le 25)  { $buckets[1]++ }
                elseif ($v -le 50)  { $buckets[2]++ }
                elseif ($v -le 100) { $buckets[3]++ }
                elseif ($v -le 200) { $buckets[4]++ }
                elseif ($v -le 500) { $buckets[5]++ }
                elseif ($v -le 1000){ $buckets[6]++ }
                else                { $buckets[7]++ }
            }
            [void]$sb.AppendLine("#### $($kv.Key) - $label")
            [void]$sb.AppendLine("")
            [void]$sb.AppendLine("| Metric | Value |")
            [void]$sb.AppendLine("|--------|-------|")
            [void]$sb.AppendLine("| Count | $($s.Count) |")
            [void]$sb.AppendLine("| Avg | $($s.Avg) ms |")
            [void]$sb.AppendLine("| P50 (Median) | $($s.P50) ms |")
            [void]$sb.AppendLine("| P90 | $($s.P90) ms |")
            [void]$sb.AppendLine("| P95 | $($s.P95) ms |")
            [void]$sb.AppendLine("| P99 | $($s.P99) ms |")
            [void]$sb.AppendLine("| Max | $($s.Max) ms |")
            [void]$sb.AppendLine("")
            [void]$sb.AppendLine("| Bucket | Count | Pct |")
            [void]$sb.AppendLine("|--------|-------|-----|")
            $bn = @('0-10ms','10-25ms','25-50ms','50-100ms','100-200ms','200-500ms','500-1000ms','>1000ms')
            for ($i = 0; $i -lt $buckets.Count; $i++) {
                [void]$sb.AppendLine("| $($bn[$i]) | $($buckets[$i]) | $([Math]::Round($buckets[$i]/$n*100,1))% |")
            }
            [void]$sb.AppendLine("")
        }
    }
}

[void]$sb.AppendLine("### 3.3 Error Breakdown")
[void]$sb.AppendLine("")
[void]$sb.AppendLine("| Plan | Label | Total | Errors | Error Rate |")
[void]$sb.AppendLine("|------|-------|-------|--------|------------|")

foreach ($kv in $allStats.GetEnumerator() | Sort-Object Name) {
    $data = $kv.Value
    foreach ($label in ($data.ByLabel.Keys | Sort-Object)) {
        $total = $data.ByLabel[$label].Count
        $fc = $data.ByLabelFail[$label].Count
        if ($fc -gt 0) {
            [void]$sb.AppendLine("| $($kv.Key) | $label | $total | $fc | $([Math]::Round($fc/$total*100,2))% |")
        }
    }
}

[void]$sb.AppendLine("")
[void]$sb.AppendLine("---")
[void]$sb.AppendLine("")
[void]$sb.AppendLine("## 4. Benchmarks")
[void]$sb.AppendLine("")
[void]$sb.AppendLine("| Metric | Excellent | Acceptable | Concerning |")
[void]$sb.AppendLine("|--------|-----------|------------|------------|")
[void]$sb.AppendLine("| WS Connect P95 | < 5ms | < 50ms | > 100ms |")
[void]$sb.AppendLine("| Auth Resp P95 | < 30ms | < 200ms | > 500ms |")
[void]$sb.AppendLine("| Send Msg P99 | < 5ms | < 20ms | > 50ms |")
[void]$sb.AppendLine("| E2E Delivery P50 | < 50ms | < 100ms | > 200ms |")
[void]$sb.AppendLine("| E2E Delivery P99 | < 200ms | < 500ms | > 1000ms |")
[void]$sb.AppendLine("| Delivery Rate | >= 99.9% | >= 99% | < 99% |")
[void]$sb.AppendLine("| Error Rate | < 0.1% | < 1% | > 5% |")

$outDir = Split-Path $OutputFile -Parent
if (-not (Test-Path $outDir)) { New-Item -ItemType Directory $outDir | Out-Null }
$sb.ToString() | Out-File -FilePath $OutputFile -Encoding utf8

Write-Host ""
Write-Host "Report saved to: $OutputFile" -ForegroundColor Green
