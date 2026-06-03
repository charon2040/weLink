# 故障注入: 关掉 Kafka 60s 再启动, 验证 outbox 兜底
# 跑这个之前先在另一个窗口启动消息压测(如 06-private-chat 或 07-group-msg)

param(
    [int]$DownSeconds = 60,
    [string]$MysqlMainContainer = "welink-mysql-main",
    [string]$MysqlShard0Container = "welink-mysql-shard-0"
)

Write-Host "==== Kafka 故障注入 (停 $DownSeconds 秒) ====" -ForegroundColor Yellow
Write-Host ""

Write-Host "停 Kafka 前: outbox_pending 表行数"
docker exec $MysqlMainContainer sh -c "mysql -uroot -p123456 -Dwelink -e 'SELECT COUNT(*) FROM outbox_pending;' 2>&1" | Select-Object -Last 2

Write-Host ""
Write-Host "→ docker stop welink-kafka" -ForegroundColor Yellow
docker stop welink-kafka | Out-Null

Write-Host "停机期间持续监控 outbox_pending..."
for ($i = 1; $i -le ($DownSeconds / 10); $i++) {
    Start-Sleep 10
    $rows = docker exec $MysqlMainContainer sh -c "mysql -uroot -p123456 -Dwelink -sN -e 'SELECT COUNT(*) FROM outbox_pending;' 2>/dev/null"
    Write-Host "  [+$($i * 10)s] outbox_pending 行数: $rows"
}

Write-Host ""
Write-Host "→ docker start welink-kafka" -ForegroundColor Green
docker start welink-kafka | Out-Null

Write-Host "恢复后继续监控 (应快速消化)..."
for ($i = 1; $i -le 12; $i++) {
    Start-Sleep 10
    $rows = docker exec $MysqlMainContainer sh -c "mysql -uroot -p123456 -Dwelink -sN -e 'SELECT COUNT(*) FROM outbox_pending;' 2>/dev/null"
    $failed = docker exec $MysqlShard0Container sh -c "mysql -uroot -p123456 -Dwelink_msg_00 -sN -e 'SELECT COUNT(*) FROM message_outbox_00 WHERE status=2;' 2>/dev/null"
    Write-Host "  [+$($i * 10)s recovered] outbox_pending=$rows  FAILED 样本=$failed"
}

Write-Host ""
Write-Host "==== 注入完成, 看 outbox_pending 是否归零 ====" -ForegroundColor Cyan
