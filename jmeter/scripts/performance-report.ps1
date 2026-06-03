param(
    [string]$ResultsDir = (Join-Path $PSScriptRoot ".." "results"),
    [string]$OutputFile = (Join-Path $PSScriptRoot ".." "results" "performance-report.md")
)

$ErrorActionPreference = "Stop"

$planOrder = @(
    "01-connection-capacity",
    "02-private-throughput",
    "03-group-throughput",
    "04-private-e2e",
    "05-group-e2e",
    "06-mixed-scenario",
    "07-multi-group-throughput",
    "08-mixed-e2e",
    "09-multi-group-e2e"
)

$planTitles = @{
    "01-connection-capacity"   = "Connection Capacity"
    "02-private-throughput"    = "Private Throughput"
    "03-group-throughput"      = "Single-Group Throughput"
    "04-private-e2e"           = "Private E2E"
    "05-group-e2e"             = "Single-Group E2E"
    "06-mixed-scenario"        = "Mixed Throughput"
    "07-multi-group-throughput"= "Multi-Group Throughput"
    "08-mixed-e2e"             = "Mixed E2E"
    "09-multi-group-e2e"       = "Multi-Group E2E"
}

function Get-OrAddLabelCounter {
    param(
        [hashtable]$Map,
        [string]$Label
    )
    if (-not $Map.ContainsKey($Label)) {
        $Map[$Label] = [ordered]@{
            Total = 0
            Ok    = 0
            Fail  = 0
        }
    }
    return $Map[$Label]
}

function Get-LabelValue {
    param(
        [hashtable]$Map,
        [string]$Label,
        [string]$Field = "Ok"
    )
    if ($Map.ContainsKey($Label)) {
        return [int]$Map[$Label][$Field]
    }
    return 0
}

function Get-ThroughputText {
    param(
        [int]$Count,
        [double]$DurationSec,
        [string]$Unit
    )
    if ($Count -le 0 -or $DurationSec -le 0) {
        return "-"
    }
    return ("{0:N2} {1}/s" -f ($Count / $DurationSec), $Unit)
}

function Parse-JtlFile {
    param([string]$Path)

    $byLabel = @{}
    $totalReqs = 0
    $totalErrors = 0
    $tsMin = [long]::MaxValue
    $tsMax = [long]::MinValue

    $reader = [System.IO.StreamReader]::new($Path)
    try {
        [void]$reader.ReadLine()
        while ($null -ne ($line = $reader.ReadLine())) {
            if ([string]::IsNullOrWhiteSpace($line)) { continue }
            $parts = $line -split ','
            if ($parts.Length -lt 9) { continue }

            $ts = [long]$parts[0]
            $label = $parts[2]
            $success = $parts[7] -eq 'true'

            if ($ts -lt $tsMin) { $tsMin = $ts }
            if ($ts -gt $tsMax) { $tsMax = $ts }

            $totalReqs++
            if (-not $success) { $totalErrors++ }

            $counter = Get-OrAddLabelCounter -Map $byLabel -Label $label
            $counter.Total++
            if ($success) {
                $counter.Ok++
            } else {
                $counter.Fail++
            }
        }
    }
    finally {
        $reader.Close()
    }

    $durationSec = if ($tsMin -ne [long]::MaxValue -and $tsMax -gt $tsMin) {
        [Math]::Round(($tsMax - $tsMin) / 1000.0, 1)
    } else {
        0.0
    }

    $errorRate = if ($totalReqs -gt 0) {
        [Math]::Round($totalErrors * 100.0 / $totalReqs, 4)
    } else {
        0.0
    }

    return [pscustomobject]@{
        Path       = $Path
        FileName   = [IO.Path]::GetFileName($Path)
        DurationSec= $durationSec
        TotalReqs  = $totalReqs
        TotalErrors= $totalErrors
        ErrorRate  = $errorRate
        ByLabel    = $byLabel
    }
}

function Build-KeyResult {
    param([string]$Plan, $Parsed)

    $labels = $Parsed.ByLabel
    $duration = [double]$Parsed.DurationSec

    switch ($Plan) {
        "01-connection-capacity" {
            $wsOk = Get-LabelValue $labels "WS Connect" "Ok"
            $authOk = Get-LabelValue $labels "Auth Resp" "Ok"
            return "WS OK=$wsOk, Auth OK=$authOk"
        }
        "02-private-throughput" {
            $sent = Get-LabelValue $labels "Send Private Msg" "Ok"
            return "Private Send OK=$sent, $(Get-ThroughputText $sent $duration 'msg')"
        }
        "03-group-throughput" {
            $sent = Get-LabelValue $labels "Send Group Msg" "Ok"
            return "Group Send OK=$sent, $(Get-ThroughputText $sent $duration 'group msg')"
        }
        "04-private-e2e" {
            $sent = Get-LabelValue $labels "Send Private Msg" "Ok"
            $recvOk = Get-LabelValue $labels "Recv Private Msg" "Ok"
            $recvFail = Get-LabelValue $labels "Recv Private Msg" "Fail"
            return "Send OK=$sent, Recv OK=$recvOk, Recv Fail=$recvFail"
        }
        "05-group-e2e" {
            $sent = Get-LabelValue $labels "Send Group Msg" "Ok"
            $recvOk = Get-LabelValue $labels "Recv Group Msg" "Ok"
            $recvFail = Get-LabelValue $labels "Recv Group Msg" "Fail"
            return "Send OK=$sent, Recv OK=$recvOk, Recv Fail=$recvFail"
        }
        "06-mixed-scenario" {
            $pvtSent = Get-LabelValue $labels "Send Private Msg" "Ok"
            $grpSent = Get-LabelValue $labels "Send Group Msg" "Ok"
            return "Private Send OK=$pvtSent, Group Send OK=$grpSent"
        }
        "07-multi-group-throughput" {
            $sent = Get-LabelValue $labels "Send Group Msg" "Ok"
            return "Multi-Group Send OK=$sent, $(Get-ThroughputText $sent $duration 'group msg')"
        }
        "08-mixed-e2e" {
            $pvtSent = Get-LabelValue $labels "Send Private Msg" "Ok"
            $pvtRecv = Get-LabelValue $labels "Recv Private Msg" "Ok"
            $grpSent = Get-LabelValue $labels "Send Group Msg" "Ok"
            $grpRecv = Get-LabelValue $labels "Recv Group Msg" "Ok"
            $recvFail = (Get-LabelValue $labels "Recv Private Msg" "Fail") + (Get-LabelValue $labels "Recv Group Msg" "Fail")
            return "Pvt Send=$pvtSent/Recv=$pvtRecv, Grp Send=$grpSent/Recv=$grpRecv, Recv Fail=$recvFail"
        }
        "09-multi-group-e2e" {
            $sent = Get-LabelValue $labels "Send Group Msg" "Ok"
            $recvOk = Get-LabelValue $labels "Recv Group Msg" "Ok"
            $recvFail = Get-LabelValue $labels "Recv Group Msg" "Fail"
            return "Send OK=$sent, Recv OK=$recvOk, Recv Fail=$recvFail"
        }
        default {
            return "-"
        }
    }
}

$jtlFiles = Get-ChildItem (Join-Path $ResultsDir "*.jtl") | Sort-Object Name
$latestByPlan = @{}
foreach ($f in $jtlFiles) {
    $planName = [IO.Path]::GetFileNameWithoutExtension($f.Name) -replace '_\d{8}_\d{6}$',''
    if (-not $latestByPlan.ContainsKey($planName) -or $f.LastWriteTime -gt $latestByPlan[$planName].LastWriteTime) {
        $latestByPlan[$planName] = $f
    }
}

$sb = [System.Text.StringBuilder]::new()
[void]$sb.AppendLine("# WeLink IM Performance Report")
[void]$sb.AppendLine("")
[void]$sb.AppendLine("Generated: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')")
[void]$sb.AppendLine("")
[void]$sb.AppendLine('This report is generated from the latest .jtl result of each current 01-09 JMeter plan.')
[void]$sb.AppendLine("")
[void]$sb.AppendLine("## Current 01-09 Summary")
[void]$sb.AppendLine("")
[void]$sb.AppendLine("| Plan | Title | Latest Result | Duration | Total Samples | Total Errors | Error Rate | Key Result |")
[void]$sb.AppendLine("|------|-------|---------------|----------|---------------|--------------|------------|------------|")

$parsedByPlan = @{}
foreach ($plan in $planOrder) {
    if (-not $latestByPlan.ContainsKey($plan)) {
        [void]$sb.AppendLine("| $plan | $($planTitles[$plan]) | - | - | - | - | - | Missing result |")
        continue
    }

    $parsed = Parse-JtlFile -Path $latestByPlan[$plan].FullName
    $parsedByPlan[$plan] = $parsed

    $keyResult = Build-KeyResult -Plan $plan -Parsed $parsed
    [void]$sb.AppendLine(('| {0} | {1} | {2} | {3}s | {4} | {5} | {6}% | {7} |' -f
            $plan,
            $planTitles[$plan],
            $parsed.FileName,
            $parsed.DurationSec,
            $parsed.TotalReqs,
            $parsed.TotalErrors,
            $parsed.ErrorRate,
            $keyResult))
}

[void]$sb.AppendLine("")
[void]$sb.AppendLine("## E2E Focus")
[void]$sb.AppendLine("")
[void]$sb.AppendLine("| Plan | Send Label | Send OK | Recv Label | Recv OK | Recv Fail |")
[void]$sb.AppendLine("|------|------------|---------|------------|---------|-----------|")

$e2eRows = @(
    @{ Plan = "04-private-e2e"; SendLabel = "Send Private Msg"; RecvLabel = "Recv Private Msg" },
    @{ Plan = "05-group-e2e"; SendLabel = "Send Group Msg"; RecvLabel = "Recv Group Msg" },
    @{ Plan = "08-mixed-e2e"; SendLabel = "Send Private Msg / Send Group Msg"; RecvLabel = "Recv Private Msg / Recv Group Msg" },
    @{ Plan = "09-multi-group-e2e"; SendLabel = "Send Group Msg"; RecvLabel = "Recv Group Msg" }
)

foreach ($row in $e2eRows) {
    if (-not $parsedByPlan.ContainsKey($row.Plan)) {
        [void]$sb.AppendLine("| $($row.Plan) | $($row.SendLabel) | - | $($row.RecvLabel) | - | - |")
        continue
    }

    $parsed = $parsedByPlan[$row.Plan]
    $labels = $parsed.ByLabel

    if ($row.Plan -eq "08-mixed-e2e") {
        $sendOk = "{0} / {1}" -f (Get-LabelValue $labels "Send Private Msg" "Ok"), (Get-LabelValue $labels "Send Group Msg" "Ok")
        $recvOk = "{0} / {1}" -f (Get-LabelValue $labels "Recv Private Msg" "Ok"), (Get-LabelValue $labels "Recv Group Msg" "Ok")
        $recvFail = "{0} / {1}" -f (Get-LabelValue $labels "Recv Private Msg" "Fail"), (Get-LabelValue $labels "Recv Group Msg" "Fail")
        [void]$sb.AppendLine("| $($row.Plan) | $($row.SendLabel) | $sendOk | $($row.RecvLabel) | $recvOk | $recvFail |")
        continue
    }

    $sendOk = Get-LabelValue $labels $row.SendLabel "Ok"
    $recvOk = Get-LabelValue $labels $row.RecvLabel "Ok"
    $recvFail = Get-LabelValue $labels $row.RecvLabel "Fail"
    [void]$sb.AppendLine("| $($row.Plan) | $($row.SendLabel) | $sendOk | $($row.RecvLabel) | $recvOk | $recvFail |")
}

[void]$sb.AppendLine("")
[void]$sb.AppendLine("## Notes")
[void]$sb.AppendLine("")
[void]$sb.AppendLine('- This script follows the current suite numbering: 01-09.')
[void]$sb.AppendLine('- Legacy 08-multi-group-e2e* result files belong to an older numbering scheme and should be archived rather than mixed into current conclusions.')
[void]$sb.AppendLine('- Current E2E conclusions should be cross-checked with backend metrics, Kafka lag and fanout queue state.')

$outDir = Split-Path $OutputFile -Parent
if (-not (Test-Path $outDir)) {
    New-Item -ItemType Directory -Path $outDir | Out-Null
}

$sb.ToString() | Out-File -FilePath $OutputFile -Encoding utf8
Write-Host "Report saved to: $OutputFile" -ForegroundColor Green
