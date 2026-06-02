# 清理压测数据 — 把 perf_ 开头的账号 + 测试群删掉, 不影响真实数据
# ⚠️ 务必确认前缀 perf_ 没冲突再跑

param(
    [string]$Prefix = "perf_",
    [string]$MysqlMainContainer = "welink-mysql-main"
)

Write-Host "==== 清理压测数据 (前缀=$Prefix) ====" -ForegroundColor Yellow
Write-Host "将删除:"
Write-Host "  - user 表里所有 username LIKE '$Prefix%' 的账号"
Write-Host "  - 对应的 friend_relation, group_member 行"
Write-Host "  - perf_group_% 命名的群"
Write-Host ""

$confirmation = Read-Host "确认? 输入 yes 继续"
if ($confirmation -ne "yes") { Write-Host "取消"; exit 0 }

$MysqlShardContainers = 0..7 | ForEach-Object { "welink-mysql-shard-$_" }

function Run-SqlOnContainer {
    param(
        [string]$Container,
        [string]$Sql,
        [string]$TempName
    )
    $tmp = New-TemporaryFile
    $Sql | Out-File $tmp.FullName -Encoding utf8 -NoNewline
    docker cp $tmp.FullName "${Container}:/tmp/$TempName" | Out-Null
    docker exec $Container sh -c "mysql -uroot -p123456 < /tmp/$TempName"
    Remove-Item $tmp.FullName -Force
}

$mainSql = @"
USE welink;
DELETE FROM friend_relation WHERE user_id IN (SELECT id FROM user WHERE username LIKE '${Prefix}%')
                              OR friend_id IN (SELECT id FROM user WHERE username LIKE '${Prefix}%');
DELETE FROM group_member WHERE user_id IN (SELECT id FROM user WHERE username LIKE '${Prefix}%')
                            OR group_id IN (SELECT id FROM group_info WHERE group_name LIKE 'perf_group_%');
DELETE FROM group_info WHERE group_name LIKE 'perf_group_%';
DELETE FROM user WHERE username LIKE '${Prefix}%';
SELECT COUNT(*) AS remaining_perf_users FROM user WHERE username LIKE '${Prefix}%';
"@
Run-SqlOnContainer -Container $MysqlMainContainer -Sql $mainSql -TempName "cleanup-main.sql"

for ($i = 0; $i -lt $MysqlShardContainers.Count; $i++) {
    $db = "welink_msg_0$i"
    $shardSql = @"
DELETE FROM ${db}.group_info WHERE group_name LIKE 'perf_group_%';
"@
    Run-SqlOnContainer -Container $MysqlShardContainers[$i] -Sql $shardSql -TempName "cleanup-shard-$i.sql"
}

Write-Host "==== 清理完成 ====" -ForegroundColor Green
