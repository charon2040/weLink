# DB 直插建群 + 大批量拉人进群, 跳过 HTTP API
# 用法: pwsh prepare-db-group.ps1 -Members 1000
#
# 流程:
#   1. 读 tokens.csv (前置: prepare-db-bulk.ps1)
#   2. 用 SQL INSERT group_info + group_member
#   3. group_info 是 BROADCAST 表 → 必须 9 个 ds 都写; 用同一 id 同一 group_no, owner_id 取第一个用户
#   4. group_member 是 unsharded → 一次 INSERT 多 VALUES

param(
    [int]$Members = 1000,
    [string]$GroupName = "",
    [string]$MysqlMainContainer = "welink-mysql-main"
)

$ErrorActionPreference = "Stop"
$dataDir = Join-Path $PSScriptRoot ".." "data"
$tokensCsv = Join-Path $dataDir "tokens.csv"
$groupInfoCsv = Join-Path $dataDir "group-info.csv"
$membersCsv = Join-Path $dataDir "group-members.csv"
$MysqlShardContainers = 0..7 | ForEach-Object { "welink-mysql-shard-$_" }

if (-not (Test-Path $tokensCsv)) {
    Write-Host "缺少 $tokensCsv, 先运行 prepare-db-bulk.ps1" -ForegroundColor Red
    exit 1
}

$tokens = Import-Csv $tokensCsv
if ($tokens.Count -lt ($Members + 1)) {
    Write-Host "tokens 池不够 $($Members + 1) 个, 用 prepare-db-bulk.ps1 -Count $($Members + 100) 扩容" -ForegroundColor Red
    exit 1
}

if ([string]::IsNullOrEmpty($GroupName)) { $GroupName = "perf_group_${Members}_$(Get-Random -Maximum 9999)" }
$owner = $tokens[0]

function Run-SqlOnContainer {
    param(
        [string]$Container,
        [string]$Sql,
        [string]$TempName = "_group.sql"
    )
    $tmp = New-TemporaryFile
    $Sql | Out-File $tmp.FullName -Encoding utf8 -NoNewline
    docker cp $tmp.FullName "${Container}:/tmp/$TempName" | Out-Null
    docker exec $Container sh -c "mysql -uroot -p123456 -BN < /tmp/$TempName 2>/dev/null" | Out-Null
    Remove-Item $tmp.FullName -Force
}

# 用毫秒时间戳生成一个雪花风格 id (足够大避免与现有冲突)
$gid = ([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() * 1000 + (Get-Random -Maximum 1000))
$gno = (Get-Random -Minimum 10000000 -Maximum 99999999).ToString()

Write-Host "==== DB 直插建 $Members-人 群: $GroupName ====" -ForegroundColor Cyan
Write-Host "  groupId = $gid"
Write-Host "  groupNo = $gno"
Write-Host "  群主    = $($owner.username) (id=$($owner.userId))"

# Step 1: 写 group_info 到主库 + 8 个 shard 容器
$escapedGroupName = $GroupName.Replace("'", "''")
Run-SqlOnContainer -Container $MysqlMainContainer -TempName "_group-main.sql" -Sql @"
INSERT INTO welink.group_info (id, group_no, group_name, owner_id, member_count, status)
VALUES ($gid, '$gno', '$escapedGroupName', $($owner.userId), $($Members + 1), 1);
"@

for ($i = 0; $i -lt $MysqlShardContainers.Count; $i++) {
    $db = "welink_msg_0$i"
    Run-SqlOnContainer -Container $MysqlShardContainers[$i] -TempName "_group-shard-$i.sql" -Sql @"
INSERT INTO ${db}.group_info (id, group_no, group_name, owner_id, member_count, status)
VALUES ($gid, '$gno', '$escapedGroupName', $($owner.userId), $($Members + 1), 1);
"@
}

# Step 2: 写 group_member (unsharded, 只在 welink 主库)
$sb = New-Object System.Text.StringBuilder
$null = $sb.AppendLine("USE welink;")
$values = @()
# 群主
$values += "($gid, $($owner.userId), 2, 0)"
# 成员
for ($i = 1; $i -le $Members; $i++) {
    $u = $tokens[$i]
    $values += "($gid, $($u.userId), 0, 0)"
    if ($values.Count -ge 500) {
        $null = $sb.AppendLine("INSERT INTO group_member (group_id, user_id, role, last_read_seq) VALUES " + ($values -join ',') + ";")
        $values = @()
    }
}
if ($values.Count -gt 0) {
    $null = $sb.AppendLine("INSERT INTO group_member (group_id, user_id, role, last_read_seq) VALUES " + ($values -join ',') + ";")
}

Run-SqlOnContainer -Container $MysqlMainContainer -Sql $sb.ToString()

# 清 Redis 群成员缓存让后端重新读
docker exec welink-redis redis-cli DEL "group:members:$gid" | Out-Null

# 写 CSV
"groupId,groupNo,ownerToken" | Out-File -FilePath $groupInfoCsv -Encoding utf8
"$gid,$gno,$($owner.accessToken)" | Add-Content -Path $groupInfoCsv -Encoding utf8

"groupId,userId,token" | Out-File -FilePath $membersCsv -Encoding utf8
for ($i = 0; $i -le $Members; $i++) {
    $u = $tokens[$i]
    "$gid,$($u.userId),$($u.accessToken)" | Add-Content -Path $membersCsv -Encoding utf8
}

Write-Host ""
Write-Host "==== 完成 ====" -ForegroundColor Green
Write-Host "groupId:    $gid"
Write-Host "groupNo:    $gno"
Write-Host "成员数:     $($Members + 1) (含群主)"
Write-Host "group-info: $groupInfoCsv"
Write-Host "members:    $membersCsv"
