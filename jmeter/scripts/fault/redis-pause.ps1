# 短暂暂停 Redis 验证 IM 容错
# 验证: 期间限流/路由失效但消息核心路径不应崩

param(
    [int]$PauseSeconds = 5
)

Write-Host "==== Redis 故障注入 (pause $PauseSeconds 秒) ====" -ForegroundColor Yellow

Write-Host "→ docker pause welink-redis"
docker pause welink-redis | Out-Null

Start-Sleep $PauseSeconds

Write-Host "→ docker unpause welink-redis"
docker unpause welink-redis | Out-Null

Write-Host "==== Redis 恢复. 后端 metrics 应有 fail-open 行为 ====" -ForegroundColor Cyan
Write-Host "查 Redis: docker exec welink-redis redis-cli INFO stats | grep total_connections_received"
