# 准备多群聊吞吐量测试数据 (多个群 + 每个群的成员, 各群成员不重叠)
# 用法: pwsh prepare-multi-group.ps1 [-GroupCount 50] [-MembersPerGroup 100]
# 前置: 先跑 prepare-db-bulk.ps1 生成 tokens.csv
# 各群成员从 tokens.csv 顺序分配, 群间不重叠

param(
    [int]$GroupCount = 50,
    [int]$MembersPerGroup = 100,
    [string]$MysqlMainContainer = "welink-mysql-main"
)

$ErrorActionPreference = "Stop"
$dataDir = Join-Path $PSScriptRoot ".." "data"
$tokensCsv = Join-Path $dataDir "tokens.csv"
$groupInfoCsv = Join-Path $dataDir "multi-group-info.csv"
$membersCsv = Join-Path $dataDir "multi-group-members.csv"
$MysqlShardContainers = 0..7 | ForEach-Object { "welink-mysql-shard-$_" }

if (-not (Test-Path $tokensCsv)) {
    Write-Host "缺少 $tokensCsv, 先运行 prepare-db-bulk.ps1" -ForegroundColor Red
    exit 1
}

$tokens = Import-Csv $tokensCsv
$totalMembersNeeded = $GroupCount * $MembersPerGroup
if ($tokens.Count -lt $totalMembersNeeded) {
    Write-Host "tokens 池 $($tokens.Count) 不够 $totalMembersNeeded 个. 用 prepare-db-bulk.ps1 -Count $($totalMembersNeeded + 100) 扩容" -ForegroundColor Red
    exit 1
}

function Run-SqlOnContainer {
    param(
        [string]$Container,
        [string]$Sql,
        [string]$TempName = "_multi-group.sql"
    )
    $tmp = New-TemporaryFile
    $Sql | Out-File $tmp.FullName -Encoding utf8 -NoNewline
    docker cp $tmp.FullName "${Container}:/tmp/$TempName" | Out-Null
    docker exec $Container sh -c "mysql -uroot -p123456 -BN < /tmp/$TempName 2>/dev/null" | Out-Null
    Remove-Item $tmp.FullName -Force
}

Write-Host ""
Write-Host ("=" * 70) -ForegroundColor Cyan
Write-Host "  准备多群聊数据: $GroupCount 个群 x $MembersPerGroup 成员" -ForegroundColor Cyan
Write-Host ("=" * 70) -ForegroundColor Cyan

"groupId,groupNo,memberCount,ownerToken" | Out-File -FilePath $groupInfoCsv -Encoding utf8
$memberRows = New-Object System.Collections.Generic.List[object]

$tokenOffset = 0

for ($g = 0; $g -lt $GroupCount; $g++) {
    $gid = ([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() * 1000 + (Get-Random -Maximum 1000) + $g * 10000)
    $gno = (Get-Random -Minimum 10000000 -Maximum 99999999).ToString()
    $groupName = "perf_mgroup_${g}_$(Get-Random -Maximum 9999)"
    $owner = $tokens[$tokenOffset]
    $escapedGroupName = $groupName.Replace("'", "''")

    Run-SqlOnContainer -Container $MysqlMainContainer -TempName "_mg-main-$g.sql" -Sql @"
INSERT INTO welink.group_info (id, group_no, group_name, owner_id, member_count, status)
VALUES ($gid, '$gno', '$escapedGroupName', $($owner.userId), $MembersPerGroup, 1);
"@

    for ($i = 0; $i -lt $MysqlShardContainers.Count; $i++) {
        $db = "welink_msg_0$i"
        Run-SqlOnContainer -Container $MysqlShardContainers[$i] -TempName "_mg-shard-$g-$i.sql" -Sql @"
INSERT INTO ${db}.group_info (id, group_no, group_name, owner_id, member_count, status)
VALUES ($gid, '$gno', '$escapedGroupName', $($owner.userId), $MembersPerGroup, 1);
"@
    }

    $sb = New-Object System.Text.StringBuilder
    $null = $sb.AppendLine("USE welink;")
    $values = @()
    for ($m = 0; $m -lt $MembersPerGroup; $m++) {
        $u = $tokens[$tokenOffset + $m]
        $role = ($m -eq 0) ? 2 : 0
        $values += "($gid, $($u.userId), $role, 0)"
        if ($values.Count -ge 500) {
            $null = $sb.AppendLine("INSERT INTO group_member (group_id, user_id, role, last_read_seq) VALUES " + ($values -join ',') + ";")
            $values = @()
        }
    }
    if ($values.Count -gt 0) {
        $null = $sb.AppendLine("INSERT INTO group_member (group_id, user_id, role, last_read_seq) VALUES " + ($values -join ',') + ";")
    }
    Run-SqlOnContainer -Container $MysqlMainContainer -Sql $sb.ToString()

    docker exec welink-redis redis-cli DEL "group:members:$gid" | Out-Null

    "$gid,$gno,$MembersPerGroup,$($owner.accessToken)" | Add-Content -Path $groupInfoCsv -Encoding utf8
    for ($m = 0; $m -lt $MembersPerGroup; $m++) {
        $u = $tokens[$tokenOffset + $m]
        $memberRows.Add([pscustomobject]@{
            GroupIndex = $g
            MemberIndex = $m
            GroupId = $gid
            UserId = $u.userId
            AccessToken = $u.accessToken
        }) | Out-Null
    }

    $tokenOffset += $MembersPerGroup
    Write-Host "  群 $g : groupId=$gid memberCount=$MembersPerGroup" -ForegroundColor Gray
}

# Interleave CSV rows by member index first, then group index. This keeps the
# first N JMeter threads spread across groups instead of hot-spotting group 0.
"groupId,userId,token" | Out-File -FilePath $membersCsv -Encoding utf8
$memberRows |
        Sort-Object MemberIndex, GroupIndex |
        ForEach-Object {
            "$($_.GroupId),$($_.UserId),$($_.AccessToken)" | Add-Content -Path $membersCsv -Encoding utf8
        }

Write-Host ""
Write-Host ("=" * 70) -ForegroundColor Green
Write-Host "  多群聊数据准备完成" -ForegroundColor Green
Write-Host ("=" * 70) -ForegroundColor Green
Write-Host "  群数:        $GroupCount"
Write-Host "  每群成员:    $MembersPerGroup"
Write-Host "  总成员记录:  $($GroupCount * $MembersPerGroup)"
Write-Host "  group-info:  $groupInfoCsv"
Write-Host "  members:     $membersCsv"
Write-Host ""
Write-Host "运行测试:" -ForegroundColor Cyan
Write-Host "  pwsh run.ps1 -Plan 07-multi-group-throughput -Threads 160 -Duration 90 -ExtraArgs `"-JdelayMs=500`""
Write-Host "  pwsh run.ps1 -Plan 09-multi-group-e2e -Threads 960 -Duration 90 -ExtraArgs `"-JsendThreads=160 -JrecvThreads=800 -JsendDelayMs=500 -JwarmupMs=10000 -JrecvDuration=120 -JdrainSec=30`""
