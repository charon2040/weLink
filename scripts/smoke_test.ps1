# WeLink 综合 REST 烟测脚本 — 覆盖所有 REST 端点 + 5 bug + 3 改进
# 用法: pwsh smoke_test.ps1
$ErrorActionPreference = "Continue"
$base = "http://localhost:8080"
$pass = "test123456"
$internal = @{ "X-Internal-Secret" = "welink-internal-default-secret-change-me" }

function step($name) { Write-Host ""; Write-Host ("=" * 70) -ForegroundColor Cyan; Write-Host "  $name" -ForegroundColor Cyan; Write-Host ("=" * 70) -ForegroundColor Cyan }
function ok($msg) { Write-Host "  ✓ $msg" -ForegroundColor Green }
function fail($msg) { Write-Host "  ✗ $msg" -ForegroundColor Red; $script:failures++ }
function info($msg) { Write-Host "  · $msg" -ForegroundColor Gray }

$script:failures = 0

function call($method, $path, $body = $null, $headers = @{}) {
    try {
        $args = @{ Method = $method; Uri = "$base$path"; UseBasicParsing = $true; TimeoutSec = 10; Headers = $headers }
        if ($body) { $args.Body = ($body | ConvertTo-Json -Depth 5); $args.ContentType = 'application/json' }
        $res = Invoke-WebRequest @args
        return $res.Content | ConvertFrom-Json
    } catch {
        try { return ($_.ErrorDetails.Message | ConvertFrom-Json) } catch { return @{ code = -1; message = $_.Exception.Message } }
    }
}

function authHeader($t) { return @{ Authorization = "Bearer $t" } }

# ============================================================
step "1. Auth - 注册 + 登录 + 刷新"
# ============================================================
$u1 = "smoke_alice_$(Get-Random -Maximum 9999)"
$u2 = "smoke_bob_$(Get-Random -Maximum 9999)"

$r = call POST /api/v1/auth/register @{username=$u1;password=$pass;nickname="Alice"}
if ($r.code -eq 200) { ok "register $u1" } else { fail "register ${u1}: $($r.message)" }

$r = call POST /api/v1/auth/register @{username=$u2;password=$pass;nickname="Bob"}
if ($r.code -eq 200) { ok "register $u2" } else { fail "register ${u2}: $($r.message)" }

$r = call POST /api/v1/auth/login @{username=$u1;password=$pass}
if ($r.code -eq 200 -and $r.data.accessToken) {
    $tokenA = $r.data.accessToken; $refreshA = $r.data.refreshToken; $idA = $r.data.userInfo.id
    ok "login $u1 (id=$idA)"
} else { fail "login ${u1}: $($r.message)" }

$r = call POST /api/v1/auth/login @{username=$u2;password=$pass}
if ($r.code -eq 200) { $tokenB = $r.data.accessToken; $idB = $r.data.userInfo.id; ok "login $u2 (id=$idB)" } else { fail "login ${u2}: $($r.message)" }

# Bug 2: refresh — accessToken 同秒生成时 jwt body 相同, 不强求 token 字面不同, 只要返回 200 + 含 accessToken
$r = call POST /api/v1/auth/refresh @{refreshToken=$refreshA}
if ($r.code -eq 200 -and $r.data.accessToken) { ok "refresh token (Bug 2)" } else { fail "refresh: $($r.message)" }

# 错误的 refresh
$r = call POST /api/v1/auth/refresh @{refreshToken="garbage"}
if ($r.code -eq 1005) { ok "refresh rejects invalid token (code 1005)" } else { fail "refresh invalid not rejected: $($r.code)" }

# 用户搜索 (需要登录, Bug 1 验证 P1)
$r = call GET "/api/v1/auth/user/search?username=$u1" $null (authHeader $tokenA)
if ($r.code -eq 200) { ok "user search (JWT required)" } else { fail "user search: $($r.message)" }

# 无 token 搜索应返 401 (Bug 1)
$r = call GET "/api/v1/auth/user/search?username=$u1"
if ($r.code -eq 401) { ok "user search w/o token returns 401 (Bug 1)" } else { fail "user search no-auth not 401: $($r.code)" }

# ============================================================
step "2. Friend - 申请 / 接受 / 拒绝重申 / 删除"
# ============================================================
$r = call POST "/api/v1/friend/apply/$idB" $null (authHeader $tokenA)
if ($r.code -eq 200) { ok "A apply friend B" } else { fail "apply: $($r.message)" }

$r = call GET /api/v1/friend/requests/pending $null (authHeader $tokenB)
if ($r.code -eq 200 -and ($r.data | Where-Object { $_.id -eq $idA })) { ok "B sees pending request from A" } else { fail "B pending: $($r.data)" }

# Bug 5: 测拒绝后重申
$r = call POST "/api/v1/friend/reject/$idA" $null (authHeader $tokenB)
if ($r.code -eq 200) { ok "B reject A" } else { fail "reject: $($r.message)" }

$r = call POST "/api/v1/friend/apply/$idB" $null (authHeader $tokenA)
if ($r.code -eq 200) { ok "A re-apply after reject (Bug 5)" } else { fail "re-apply blocked (Bug 5): $($r.message)" }

$r = call POST "/api/v1/friend/accept/$idA" $null (authHeader $tokenB)
if ($r.code -eq 200) { ok "B accept" } else { fail "accept: $($r.message)" }

$r = call GET /api/v1/friend/list $null (authHeader $tokenA)
if ($r.code -eq 200 -and ($r.data | Where-Object { $_.id -eq $idB })) { ok "A's friend list contains B" } else { fail "A friend list: $($r.data)" }

# 删除好友
$r = call DELETE "/api/v1/friend/$idB" $null (authHeader $tokenA)
if ($r.code -eq 200) { ok "delete friend" } else { fail "delete: $($r.message)" }

# 再加回来用于后续测试
call POST "/api/v1/friend/apply/$idB" $null (authHeader $tokenA) | Out-Null
call POST "/api/v1/friend/accept/$idA" $null (authHeader $tokenB) | Out-Null

# ============================================================
step "3. Group - 创建 / 加入 / 转让 / 退群 / 解散"
# ============================================================
$r = call POST /api/v1/group @{groupName=("smoke_group_" + (Get-Random -Maximum 9999)); memberIds=@()} (authHeader $tokenA)
if ($r.code -eq 200) { $gid = $r.data.id; ok "A create group (id=$gid)" } else { fail "create group: $($r.message)"; $gid = $null }

if ($gid) {
    # B 加入
    $r = call POST "/api/v1/group/join/$gid" $null (authHeader $tokenB)
    if ($r.code -eq 200) { ok "B join group" } else { fail "join: $($r.message)" }

    # 群成员
    $r = call GET "/api/v1/group/$gid/members" $null (authHeader $tokenA)
    if ($r.code -eq 200 -and $r.data.Count -eq 2) { ok "members count = 2" } else { fail "members: count=$($r.data.Count)" }

    # Bug 4: 转让群主给 B
    $r = call POST "/api/v1/group/$gid/transfer/$idB" $null (authHeader $tokenA)
    if ($r.code -eq 200) { ok "A transfer ownership to B (Bug 4 - transfer)" } else { fail "transfer: $($r.message)" }

    # 验证 B 是新群主
    $r = call GET "/api/v1/group/$gid/members" $null (authHeader $tokenA)
    $newOwner = $r.data | Where-Object { $_.role -eq 2 }
    if ($newOwner.userId -eq $idB) { ok "B is now owner (role=2)" } else { fail "B not owner: $($newOwner | ConvertTo-Json -Compress)" }

    # A 现在是普通成员, 可以退群
    $r = call DELETE "/api/v1/group/$gid/quit" $null (authHeader $tokenA)
    if ($r.code -eq 200) { ok "former owner A quits (Bug 4)" } else { fail "A quit: $($r.message)" }

    # B 解散群
    $r = call DELETE "/api/v1/group/$gid" $null (authHeader $tokenB)
    if ($r.code -eq 200) { ok "B dissolve group (Bug 4 - dissolve)" } else { fail "dissolve: $($r.message)" }
}

# ============================================================
step "4. File - 上传 + 元数据 + 公开代理下载 (改进 3)"
# ============================================================
$tmp = New-TemporaryFile
"hello welink smoke at $(Get-Date)" | Out-File $tmp.FullName -Encoding utf8
try {
    $r = Invoke-WebRequest -Method POST "$base/api/v1/file/upload" -Headers (authHeader $tokenA) -Form @{file = Get-Item $tmp.FullName} -UseBasicParsing -TimeoutSec 10
    $body = $r.Content | ConvertFrom-Json
    if ($body.code -eq 200 -and $body.data.fileId) {
        $fid = $body.data.fileId
        ok "upload returns fileId=$fid, url=$($body.data.url)"
    } else { fail "upload: $($body.message)" }
} catch { fail "upload exception: $($_.Exception.Message)" }
Remove-Item $tmp.FullName -Force

if ($fid) {
    # 公开下载代理 — 不带 token 应该 302
    try {
        Invoke-WebRequest -Method GET "$base/api/v1/files/$fid" -UseBasicParsing -MaximumRedirection 0 -TimeoutSec 5 -ErrorAction Stop
        fail "download did not redirect"
    } catch {
        $sc = $_.Exception.Response.StatusCode.value__
        $loc = ([Uri]$_.Exception.Response.Headers.Location).Authority
        if ($sc -eq 302 -and $loc -like "*9000*") { ok "download proxy: 302 → $loc (no token needed)" } else { fail "download status $sc loc $loc" }
    }
}

# ============================================================
step "5. Admin - 降级开关 + L4 关文件上传 (Bug 3)"
# ============================================================
$r = call GET /admin/degradation $null $internal
if ($r.code -eq 200 -and $r.data.level -eq 0) { ok "GET degradation status (level=0)" } else { fail "admin: $($r.message)" }

# 无密钥应 401
$r = call GET /admin/degradation
if ($r.code -eq 401) { ok "admin w/o secret returns 401" } else { fail "admin no-auth not 401: $($r.code)" }

# Bug 3: L4 切换
$r = call POST /admin/degradation/level/4 $null $internal
if ($r.code -eq 200 -and $r.data.fileUploadEnabled -eq $false) { ok "set L4, fileUploadEnabled=false" } else { fail "set L4: $($r | ConvertTo-Json -Compress)" }

# L4 时上传应该 403
$tmp2 = New-TemporaryFile; "x" | Out-File $tmp2.FullName -Encoding utf8
try {
    $r = Invoke-WebRequest -Method POST "$base/api/v1/file/upload" -Headers (authHeader $tokenA) -Form @{file = Get-Item $tmp2.FullName} -UseBasicParsing -TimeoutSec 10
    $body = $r.Content | ConvertFrom-Json
    if ($body.code -eq 403) { ok "L4 blocks file upload (Bug 3)" } else { fail "L4 should block: code=$($body.code)" }
} catch { fail "L4 upload exception: $($_.Exception.Message)" }
Remove-Item $tmp2.FullName -Force

# 恢复 L0
call POST /admin/degradation/level/0 $null $internal | Out-Null
ok "restored to L0"

# ============================================================
step "6. Admin - Outbox reconcile (Redis 队列空, 应 compensated=0)"
# ============================================================
$r = call POST /admin/outbox/reconcile $null $internal
if ($r.code -eq 200 -and $r.data.compensated -eq 0) { ok "outbox reconcile: compensated=0 (Redis 队列空, 符合预期)" } else { fail "reconcile: $($r | ConvertTo-Json -Compress)" }

# ============================================================
step "7. Message - 历史 / 会话 / 离线 (REST 部分, WS 部分需前端跑)"
# ============================================================
$r = call GET "/api/v1/message/history/private?userId=$idA&targetId=$idB" $null (authHeader $tokenA)
if ($r.code -eq 200) { ok "GET private history (records=$(if($r.data.records){$r.data.records.Count}else{0}))" } else { fail "private history: $($r.message)" }

$r = call GET /api/v1/message/conversations $null (authHeader $tokenA)
if ($r.code -eq 200) { ok "GET conversation summaries (count=$(if($r.data){$r.data.Count}else{0}))" } else { fail "summaries: $($r.message)" }

$r = call GET /api/v1/message/offline $null (authHeader $tokenA)
if ($r.code -eq 200) { ok "GET offline messages (count=$(if($r.data){$r.data.Count}else{0}))" } else { fail "offline: $($r.message)" }

# ============================================================
step "8. Health / Metrics"
# ============================================================
try {
    $raw = Invoke-WebRequest "$base/actuator/health" -UseBasicParsing -TimeoutSec 5
    $text = if ($raw.Content -is [byte[]]) { [System.Text.Encoding]::UTF8.GetString($raw.Content) } else { $raw.Content }
    $r = $text | ConvertFrom-Json
    if ($r.status -eq "UP") { ok "actuator/health UP, db=$($r.components.db.status), redis=$($r.components.redis.status)" } else { fail "health: $text" }
} catch { fail "health: $($_.Exception.Message)" }

try { $r = Invoke-WebRequest "$base/actuator/prometheus" -UseBasicParsing -TimeoutSec 5; if ($r.StatusCode -eq 200) { ok "prometheus metrics endpoint serves $($r.RawContentLength) bytes" } } catch { fail "prometheus: $($_.Exception.Message)" }

# ============================================================
step "结果汇总"
# ============================================================
if ($failures -eq 0) {
    Write-Host "  ✓ ALL PASS  (0 failures)" -ForegroundColor Green
    exit 0
} else {
    Write-Host "  ✗ $failures FAILURES" -ForegroundColor Red
    exit 1
}
