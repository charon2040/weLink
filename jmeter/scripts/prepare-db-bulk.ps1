# 直接 DB 批量准备压测账号 + 本地签 JWT 跳过 BCrypt
# 比 HTTP 注册快 1000+ 倍 (1 万账号: HTTP 16 分钟 → DB+JWT < 5 秒)
#
# 用法: pwsh prepare-db-bulk.ps1 -Count 10000
#
# 流程:
#   1. 通过 HTTP 注册 1 个 seed 账号 (一次性走 BCrypt)
#   2. 读 seed 账号的 password hash (BCrypt 同密码任一 hash 都能验证, 可批量复用)
#   3. SQL INSERT N 个账号, 全部共享 seed 的 hash
#   4. 读所有新账号的 id
#   5. PowerShell 本地用 HMAC-SHA384 + JWT secret 签出 accessToken
#   6. 写 usernames.csv + tokens.csv
#
# 跑完即可直接喂给 03-history-fetch / 04-ack-burst / 05-ws-longlived 等 plan

param(
    [int]$Count = 10000,
    [string]$Prefix = "perf_",
    [string]$Password = "perf123456",
    [string]$JwtSecret = "WeLinkJwtSecretKey2026VeryLongSecretKeyForSecurity",
    [int]$AccessTokenSeconds = 7200,
    [string]$ApiBase = "http://localhost:8080",
    [string]$MysqlContainer = "welink-mysql-main"
)

$ErrorActionPreference = "Stop"
$dataDir = Join-Path $PSScriptRoot ".." "data"
if (-not (Test-Path $dataDir)) { New-Item -ItemType Directory $dataDir | Out-Null }
$usersCsv = Join-Path $dataDir "usernames.csv"
$tokensCsv = Join-Path $dataDir "tokens.csv"

# ---------------- Helpers ----------------

function Encode-Base64Url($bytes) {
    return [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

function New-Jwt {
    param([long]$UserId, [string]$Username, [string]$Secret, [int]$ExpSeconds = 7200)
    $headerJson = '{"alg":"HS384"}'
    $now = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    $payloadJson = "{`"sub`":`"$Username`",`"userId`":$UserId,`"iat`":$now,`"exp`":$($now + $ExpSeconds)}"

    $h = Encode-Base64Url ([Text.Encoding]::UTF8.GetBytes($headerJson))
    $p = Encode-Base64Url ([Text.Encoding]::UTF8.GetBytes($payloadJson))

    $hmac = New-Object System.Security.Cryptography.HMACSHA384
    $hmac.Key = [Text.Encoding]::UTF8.GetBytes($Secret)
    $sig = $hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes("$h.$p"))
    $s = Encode-Base64Url $sig

    return "$h.$p.$s"
}

function Run-Sql {
    param([string]$Sql)
    $tmp = New-TemporaryFile
    $Sql | Out-File $tmp.FullName -Encoding utf8 -NoNewline
    docker cp $tmp.FullName "${MysqlContainer}:/tmp/_perf.sql" | Out-Null
    $result = docker exec $MysqlContainer sh -c "mysql -uroot -p123456 -BN < /tmp/_perf.sql 2>/dev/null"
    Remove-Item $tmp.FullName -Force
    return $result
}

# ---------------- 主流程 ----------------

Write-Host "==== 批量准备 $Count 个压测账号 (走 DB 直插)====" -ForegroundColor Cyan
Write-Host "前缀:     $Prefix"
Write-Host "密码:     $Password (BCrypt 共享 hash)"
Write-Host "MySQL:    $MysqlContainer"
Write-Host ""

# Step 1: 注册种子账号拿 BCrypt hash
$seedName = "${Prefix}seed_$(Get-Random -Maximum 999999)"
Write-Host "→ Step 1: 注册种子账号 $seedName (一次性 BCrypt)" -ForegroundColor Yellow
$seedBody = @{ username = $seedName; password = $Password; nickname = "seed" } | ConvertTo-Json -Compress
try {
    $r = Invoke-WebRequest -Method POST "$ApiBase/api/v1/auth/register" -Body $seedBody -ContentType 'application/json' -UseBasicParsing -TimeoutSec 10
    if (($r.Content | ConvertFrom-Json).code -ne 200) { throw "seed 注册失败" }
} catch {
    Write-Host "  种子注册失败: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}

# Step 2: 读 seed 账号的 password hash
Write-Host "→ Step 2: 读种子 password hash" -ForegroundColor Yellow
$seedHash = (Run-Sql "USE welink; SELECT password FROM user WHERE username='$seedName';").Trim()
if (-not $seedHash) { Write-Host "  读 hash 失败" -ForegroundColor Red; exit 1 }
Write-Host "  seed hash 长度: $($seedHash.Length)"

# Step 3: 批量生成 SQL INSERT, 共享 seed hash
Write-Host "→ Step 3: 生成 SQL INSERT 批量 $Count 条" -ForegroundColor Yellow
$sb = New-Object System.Text.StringBuilder
$null = $sb.AppendLine("USE welink;")
# 先删可能存在的同名账号, 避免 UNIQUE 冲突 (只删 perf_user_ 前缀)
$null = $sb.AppendLine("DELETE FROM user WHERE username LIKE '${Prefix}user_%';")
# 批量 INSERT, 每 1000 行一批避免 SQL 太长
$batchSize = 1000
$inserted = 0
while ($inserted -lt $Count) {
    $batchEnd = [Math]::Min($inserted + $batchSize, $Count)
    $values = @()
    for ($i = $inserted; $i -lt $batchEnd; $i++) {
        $u = "${Prefix}user_$i"
        $n = "PerfUser$i"
        $values += "('$u','$seedHash','$n',1)"
    }
    $null = $sb.AppendLine("INSERT INTO user (username, password, nickname, status) VALUES " + ($values -join ',') + ";")
    $inserted = $batchEnd
}
Write-Host "  执行 SQL (会输出累计影响行数)..."
$null = Run-Sql $sb.ToString()
Write-Host "  完成"

# Step 4: 读所有新账号的 id
Write-Host "→ Step 4: 读所有新账号 id" -ForegroundColor Yellow
$rows = Run-Sql "USE welink; SELECT id, username FROM user WHERE username LIKE '${Prefix}user_%' ORDER BY id;"
$users = @()
foreach ($line in ($rows -split "`n")) {
    if ($line -match '^\s*(\d+)\s+(\S+)\s*$') {
        $users += [PSCustomObject]@{ id = $matches[1]; username = $matches[2] }
    }
}
Write-Host "  读到 $($users.Count) 个用户"

if ($users.Count -eq 0) {
    Write-Host "  读 user 失败" -ForegroundColor Red
    exit 1
}

# Step 5: 本地签 JWT
Write-Host "→ Step 5: 本地签 $($users.Count) 个 accessToken" -ForegroundColor Yellow
"username,password" | Out-File -FilePath $usersCsv -Encoding utf8
"username,userId,accessToken" | Out-File -FilePath $tokensCsv -Encoding utf8

$t0 = Get-Date
foreach ($u in $users) {
    $token = New-Jwt -UserId ([long]$u.id) -Username $u.username -Secret $JwtSecret -ExpSeconds $AccessTokenSeconds
    "$($u.username),$Password" | Add-Content -Path $usersCsv -Encoding utf8
    "$($u.username),$($u.id),$token" | Add-Content -Path $tokensCsv -Encoding utf8
}
$elapsed = ((Get-Date) - $t0).TotalSeconds
Write-Host "  完成, 耗时 $('{0:F1}' -f $elapsed) 秒"

# Step 6: 验证一个 token 是否真能用
Write-Host "→ Step 6: 验证 token 有效性" -ForegroundColor Yellow
$testUser = $users[0]
$testToken = (Get-Content $tokensCsv | Select-Object -Skip 1 -First 1).Split(',')[2]
try {
    $verify = Invoke-WebRequest "$ApiBase/api/v1/friend/list" -Headers @{Authorization = "Bearer $testToken"} -UseBasicParsing -TimeoutSec 5
    $vc = ($verify.Content | ConvertFrom-Json).code
    if ($vc -eq 200) {
        Write-Host "  ✓ token 有效 (code=200)" -ForegroundColor Green
    } else {
        Write-Host "  ✗ token 无效 (code=$vc), 检查 JwtSecret 配置是否跟后端一致" -ForegroundColor Red
    }
} catch {
    Write-Host "  ✗ token 验证异常: $($_.Exception.Message)" -ForegroundColor Red
}

Write-Host ""
Write-Host "==== 完成 ====" -ForegroundColor Green
Write-Host "账号数:    $($users.Count)"
Write-Host "usernames: $usersCsv"
Write-Host "tokens:    $tokensCsv"
Write-Host ""
Write-Host "现在可以直接跑 plan 了, 不用再 prepare-tokens.ps1"
