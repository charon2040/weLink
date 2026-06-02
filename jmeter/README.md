# WeLink JMeter 压测套件

> 用 JMeter 5.6.3 跑 WeLink 各场景的性能/并发测试。所有 plan 都接受命令行参数（`-Jthreads` / `-Jduration` 等），不用改 .jmx 也能调参数。

## 目录结构

```
jmeter/
├── README.md                       ← 本文档
├── plans/                          ← JMeter 测试计划 (.jmx)
│   ├── 01-connection-capacity.jmx   5000 并发 WebSocket 连接
│   ├── 02-private-throughput.jmx    私聊消息吞吐量 (7-8K msg/s)
│   ├── 03-group-throughput.jmx      群聊消息吞吐量 (7-8K msg/s)
│   ├── 04-private-e2e.jmx           私聊端到端投递可靠性 (99%+)
│   ├── 05-group-e2e.jmx            群聊端到端投递可靠性
│   └── 06-mixed-scenario.jmx       混合场景 (70%私聊+30%群聊)
├── data/                           ← 测试数据 CSV (脚本自动生成)
│   ├── usernames.csv
│   ├── tokens.csv
│   ├── private-pairs.csv
│   ├── group-info.csv
│   ├── group-members.csv
│   ├── e2e-private-senders.csv
│   ├── e2e-private-receivers.csv
│   ├── e2e-group-senders.csv
│   └── e2e-group-receivers.csv
├── scripts/                        ← 辅助脚本 (PowerShell)
│   ├── prepare-all.ps1             ← 一键准备全部压测数据
│   ├── prepare-e2e.ps1             ← 准备 E2E 测试数据
│   ├── prepare-db-bulk.ps1         ← DB 直插批量注册
│   ├── prepare-db-pairs.ps1        ← DB 直插私聊配对
│   ├── prepare-db-group.ps1        ← DB 直插群组
│   ├── restart-backend.ps1         ← 重启后端服务
│   ├── rebuild-middleware.ps1      ← 重建中间件
│   ├── reset-and-prepare.ps1       ← 一键重置+准备
│   ├── run.ps1                     ← 跑指定 plan + 出 HTML 报告
│   ├── cleanup.ps1                 ← 清理压测数据
│   └── fault/                      ← 故障注入
│       ├── kafka-outage.ps1
│       ├── redis-pause.ps1
│       └── degradation.ps1
├── results/                        ← JTL 结果文件 (gitignore)
└── reports/                        ← HTML 报告 (gitignore)
```

---

## 前置准备

### 1. 安装 WebSocket 插件（如果只跑 HTTP plan 可以跳过）

JMeter 默认不支持 WebSocket，需要装插件：

1. 下载 `JMeter WebSocket Samplers by Peter Doornbosch`：  
   https://bitbucket.org/pjtr/jmeter-websocket-samplers/downloads/
2. 把 `JMeterWebSocketSamplers-x.x.x.jar` 放到 `apache-jmeter-5.6.3/lib/ext/`
3. 重启 JMeter

> 提示：也可以装 JMeter Plugins Manager (`jmeter-plugins-manager.jar`) 后通过 GUI Options → Plugins Manager 装 "WebSocket Samplers by Peter Doornbosch"。

### 2. 启动 WeLink 服务

```powershell
# 中间件
docker compose -p welink-main up -d

# 后端
cd G:\WeLink
java -jar target\WeLink-0.0.1-SNAPSHOT.jar
```

健康检查：`curl http://localhost:8080/actuator/health`

### 3. 准备测试数据

**⚡ 推荐：一键准备（DB 直插，秒级完成）**

```powershell
cd G:\WeLink\jmeter\scripts
pwsh prepare-all.ps1 -Users 10000 -Pairs 1000 -Members 1000
```

约 **3-5 秒**完成 1 万账号 + 1000 对私聊好友 + 1000 人群（含群主）。

原理：
- **账号**：用 SQL 直接 INSERT 共享一个 BCrypt hash（同密码 hash 可复用），跳过 BCrypt 计算
- **token**：PowerShell 本地用 HMAC-SHA384 + JWT secret 签出 accessToken，不走 HTTP login
- **好友/群成员**：直接 INSERT friend_relation / group_member（unsharded 表）+ 9 个 ds 都写 group_info（broadcast 表）

对比 HTTP API 准备：
| 方式 | 1 万账号耗时 | 1 万 token 耗时 |
|---|---|---|
| HTTP API（prepare-users + prepare-tokens） | 16 分钟（BCrypt × 1 万） | 16 分钟 |
| **DB 直插（prepare-db-bulk）** | **< 5 秒** | **< 1 秒** |

### 也可以分步骤

| Plan | 需要的数据 | 准备命令 |
|---|---|---|
| 01-register | 无 | — |
| 02-login | usernames.csv | `pwsh prepare-db-bulk.ps1 -Count 1000`（推荐） 或 `pwsh prepare-users.ps1 -Count 1000` |
| 03-history-fetch / 04-ack-burst / 05-ws-longlived | tokens.csv | 同上（DB 模式自动写 tokens.csv） |
| 06-private-chat | private-pairs.csv | + `pwsh prepare-db-pairs.ps1 -Pairs 1000` |
| 07-group-msg | group-info.csv + group-members.csv | + `pwsh prepare-db-group.ps1 -Members 1000` |

走 HTTP 的脚本（`prepare-users.ps1 / prepare-tokens.ps1 / prepare-private-pairs.ps1 / prepare-group.ps1`）仍保留作为对比/小规模测试用。

---

## 跑压测

### 通用命令

```powershell
pwsh scripts/run.ps1 -Plan <plan-name-without-jmx> [-Threads N] [-Duration secs] [-Ramp secs] [-ExtraArgs "..."]
```

跑完会自动产 HTML 报告到 `reports/<plan>_<timestamp>/index.html`。

### ⚡ 一键冲量（推荐先跑）

```powershell
cd G:\WeLink\jmeter\scripts
pwsh stress.ps1
```

约 30 分钟跑完一整套高压：历史拉取 10K QPS / ACK 30K QPS / 5000 长连接 / 私聊 5K msg/s / 群发 5K 推送/s。出 5 份 HTML 报告。

### 三档压力等级

每个 plan 都给了对应的参数集，按目标选：

| Plan | 冒烟（验证能跑） | 基线（合理负载） | **冲量（极限承载）** |
|---|---|---|---|
| **01-register** | `-Threads 10 -Jloops=10`（100 次） | `-Threads 50 -Jloops=20`（1000 次） | `-Threads 100 -Jloops=100`（10000 次）|
| **02-login** | `-Threads 50 -Duration 30` | `-Threads 200 -Duration 60` | `-Threads 500 -Duration 120` |
| **03-history-fetch** | `-Threads 100 -Duration 30` | `-Threads 300 -Duration 60` | **`-Threads 500 -Duration 120`** ← 期待 10K+ QPS |
| **04-ack-burst** | `-Threads 200 -Duration 30` | `-Threads 500 -Duration 60` | **`-Threads 1000 -Duration 60`** ← 期待 30K+ QPS |
| **05-ws-longlived** | `-Threads 500 -Duration 300 -Ramp 60` | `-Threads 5000 -Duration 600 -Ramp 120` | **`-Threads 20000 -Duration 1800 -Ramp 300`** ← 2 万连接 30 min |
| **06-private-chat** | `-Threads 50 -ExtraArgs "-Jrate=3000"` | `-Threads 200 -ExtraArgs "-Jrate=60000"` | **`-Threads 500 -Duration 180 -ExtraArgs "-Jrate=300000 -Jmessages=1800"`** ← 5K msg/s |
| **07-group-msg** | `-ExtraArgs "-Jrate=600"` | `-ExtraArgs "-Jrate=3000"` | **`-ExtraArgs "-Jrate=12000 -Jmessages=24000"`** ← 200 msg/s × 50 成员 = 10K 推送/s |

冲量数字的来源：
- HTTP QPS 上限 = Tomcat 1000 线程 × 1/平均响应时间。Redis 路径 1-2 ms → 50K+ 理论 QPS
- WS 长连接 = 6GiB 内存 / 50KB per conn = 12 万理论，安全水位取 20% = **2 万**
- 消息 msg/s = Netty business-threads 48 × 1/5ms = **9.6K 理论**，实际 5K-15K
- 群发推送 = 单条消息扇出到 N 个成员，每条本地推送 ~1ms，单线程 1K/s × N

### Phase 1：HTTP 基线（不需要 WS 插件）

```powershell
# 注册 — 由于 BCrypt CPU 限, QPS 看核数 (4 核 ~40, 8 核 ~80, 16 核 ~160)
pwsh scripts/run.ps1 -Plan 01-register -Threads 100 -Ramp 30 -ExtraArgs "-Jloops=100"

# 登录 — 同上 CPU 瓶颈
pwsh scripts/run.ps1 -Plan 02-login -Threads 500 -Duration 120 -Ramp 30

# 历史拉取 — 期待 10K+ QPS
pwsh scripts/run.ps1 -Plan 03-history-fetch -Threads 500 -Duration 120 -Ramp 30

# ACK 突发 — 期待 30K+ QPS
pwsh scripts/run.ps1 -Plan 04-ack-burst -Threads 1000 -Duration 60 -Ramp 20
```

### Phase 2：WebSocket 极限承载

```powershell
# 2 万长连接保持 30 分钟（要先调 JMeter 堆和 OS 文件描述符, 见下方注意事项）
pwsh scripts/run.ps1 -Plan 05-ws-longlived -Threads 20000 -Duration 1800 -Ramp 300 -ExtraArgs "-Jhb.loops=60"

# 私聊 5K msg/s（500 对用户每对 10 msg/s）
pwsh scripts/run.ps1 -Plan 06-private-chat -Threads 500 -Duration 180 -Ramp 30 -ExtraArgs "-Jrate=300000 -Jmessages=1800"

# 群消息 10K 推送/s（200 msg/s 群发 × 50 人群）
pwsh scripts/run.ps1 -Plan 07-group-msg -Duration 120 -ExtraArgs "-Jrate=12000 -Jmessages=24000"
```

---

## ⚙️ JMeter 客户端调优（跑万级长连接前必看）

跑 1 万 + WS 长连接时 JMeter 自身会成为瓶颈，要先调：

### 1. JMeter JVM 堆

编辑 `apache-jmeter-5.6.3/bin/jmeter.bat`，找到 `set HEAP`，改成：

```
set HEAP=-Xms4g -Xmx8g -XX:MaxMetaspaceSize=512m
```

10K 长连接需要约 1.5-2 GiB 堆，给 8G 安全。

### 2. Windows OS 限制

打开 PowerShell（管理员）：

```powershell
# 临时把当前会话的 TCP 客户端端口范围扩大
netsh int ipv4 set dynamicport tcp start=10000 num=55000
# 看当前值
netsh int ipv4 show dynamicport tcp
```

如果还跑不够，可以改注册表 `HKLM\SYSTEM\CurrentControlSet\Services\Tcpip\Parameters\MaxUserPort = 65534`。

### 3. 后端连接数限制

WeLink 默认配置已经能扛：
- Tomcat threads.max=1000 / accept-count=500
- MySQL max-connections=500
- HikariCP 30 × 9 = 270 

如果要冲超过，调 `application.properties`：

```properties
server.tomcat.threads.max=2000
server.tomcat.accept-count=2000
```

### 4. JMeter 多机分布式（终极方案）

单台 JMeter 客户端通常 1.5-2 万连接到顶。再往上要分布式：

```bash
# Master 机器
jmeter -n -t plan.jmx -R slave1.ip,slave2.ip,slave3.ip -l result.jtl
# Slave 机器跑
jmeter-server.bat
```

3 台 Slave × 2 万 = 6 万连接。

---

### 参数说明

| 参数 | 含义 | 默认 |
|---|---|---|
| `-Threads` / `-Jthreads` | 虚拟用户数 | 各 plan 不同 |
| `-Duration` / `-Jduration` | 持续秒数 | 60 |
| `-Ramp` / `-Jramp` | ramp-up 秒数 | 20 |
| `-Jhost` | 后端 host | localhost |
| `-Jport` | REST 端口 | 8080 |
| `-Jwsport` | WS 端口 | 8081 |
| `-Jrate` | 速率限制（ConstantThroughputTimer，msg/分钟）| 60 |
| `-Jmessages` | 每个 thread 发送的消息数 | 60 |
| `-Jhb.loops` | 长连接 plan 心跳次数（每次 30s） | 60 = 30 分钟 |

---

## 看报告

### HTML 报告位置

`reports/<plan>_<timestamp>/index.html` — 用浏览器打开。

包含：

- **Statistics**：每个请求的样本数、平均、P50/P90/P95/P99、错误率
- **APDEX**：满意度指标
- **Response Times Over Time**：响应时间曲线
- **Active Threads Over Time**：在线虚拟用户数曲线
- **TPS / Hits Per Second**：吞吐量曲线
- **Errors**：错误分类汇总

### 关键指标判定

| 场景 | 通过门槛 | 失败 |
|---|---|---|
| WS 连接容量 | **5000 并发 0% 错误率** | 错误率 > 5% |
| 私聊吞吐 | **msg/s ≥ 5K；错误率 0%** | msg/s < 3K |
| 群聊吞吐 | **msg/s ≥ 5K；错误率 < 1%** | msg/s < 3K |
| 私聊 E2E 投递 | **投递率 ≥ 99%** | 投递率 < 90% |
| 群聊 E2E 投递 | **服务端推送率 ≥ 60%** | 服务端推送率 < 30% |
| 混合场景 | **msg/s ≥ 5K；错误率 0%** | 错误率 > 5% |

### 最新压测结果（2026-05-31，第6轮优化后）

| 测试 | 关键指标 | 结果 |
|------|----------|------|
| 01 连接容量 | 5000 并发连接 | 0% 错误率 ✅ |
| 02 私聊吞吐 | 958K 条消息 | 7,985 msg/s, 0% 错误率 ✅ |
| 03 群聊吞吐 | 910K 条消息 | 7,585 msg/s, 0.04% 错误率 ✅ |
| 04 私聊 E2E | 投递率 | 99.19%（服务端 100%）✅ |
| 05 群聊 E2E | 投递率 | 21.20%（服务端推送 61.8%，JMeter 工具限制）⚠️ |
| 06 混合场景 | 吞吐量 | 8,498 msg/s, 0% 错误率 ✅ |

**延迟百分位数据**：

| 操作 | p50 | p95 | p99 | max |
|------|-----|-----|-----|-----|
| WS Connect (5000 连接) | 1ms | 2ms | 2ms | 99ms |
| Auth Resp (5000 连接) | 3ms | 8ms | 24ms | 222ms |
| Send Private Msg | 0ms | 1ms | 1ms | 10ms |
| Send Group Msg | 0ms | 1ms | 1ms | 7ms |
| Recv Private Msg (E2E) | 211ms | 229ms | 249ms | 2101ms |
| Recv Group Msg (E2E) | 51ms | 79ms | 108ms | 3235ms |

---

## 故障注入演练

跑着压测时另开窗口注入故障，看系统行为：

```powershell
# 关 Kafka 60 秒, 观察 outbox 兜底
pwsh scripts/fault/kafka-outage.ps1 -DownSeconds 60

# 暂停 Redis 5 秒
pwsh scripts/fault/redis-pause.ps1 -PauseSeconds 5

# 切到 L3 降级（关大群推送）
pwsh scripts/fault/degradation.ps1 -Level 3
# 恢复
pwsh scripts/fault/degradation.ps1 -Level 0
```

### 推荐演练剧本

**剧本 A：Kafka 故障恢复**

```powershell
# 终端 1: 启动私聊压测（产生持续 outbox 写入）
pwsh scripts/run.ps1 -Plan 06-private-chat -Threads 200 -Duration 300 -ExtraArgs "-Jrate=200 -Jmessages=300"

# 终端 2: 等 30 秒后注入
Start-Sleep 30
pwsh scripts/fault/kafka-outage.ps1 -DownSeconds 60

# 看后端日志: outbox_pending 应堆积; Kafka 恢复后 5 分钟内清空
```

**剧本 B：降级保住核心**

```powershell
# 终端 1: 启动群消息高压（让 CPU 飙到 80%+）
pwsh scripts/run.ps1 -Plan 07-group-msg -Duration 180 -ExtraArgs "-Jrate=3000 -Jmessages=3000"

# 终端 2: 切 L3 看大群推送停止
Start-Sleep 30
pwsh scripts/fault/degradation.ps1 -Level 3

# 期待: 后端 metrics 显示 messageDeliverLocal 速率断崖下降, 但单聊仍正常
# 恢复
Start-Sleep 30
pwsh scripts/fault/degradation.ps1 -Level 0
```

---

## 清理

跑完压测后清理数据：

```powershell
pwsh scripts/cleanup.ps1
```

会删除：
- `user` 表里所有 `perf_*` 用户
- 相关的 friend_relation、group_member
- `perf_group_*` 群

不影响真实数据。

---

## 常见问题

### Q：JMeter 报 "无法找到 WebSocket Sampler"

A：装 `JMeter WebSocket Samplers by Peter Doornbosch` 插件到 `apache-jmeter-5.6.3/lib/ext/`。

### Q：HTML 报告生成失败

A：看 `results/<plan>_<timestamp>.log`，常见原因是结果集太大（> 1GB）。降低 -Jthreads 或 -Jduration 重试。

### Q：登录 QPS 上不去

A：**正常**。BCrypt cost=10 是 CPU bound，单核每秒 ~10 次。4 核机器 QPS 上限 40，加机器才能提速。**这是设计取舍**（防止暴力破解），不是性能 bug。

### Q：跑 ws-longlived 大量 "Cannot read" 错

A：可能是后端 idle timeout 太短。检查 `welink.websocket.heartbeat-timeout` 应 ≥ 60（前端心跳 30s × 2）。

### Q：私聊压测出现 "Rate limit exceeded"

A：触发了 per-user 限流（默认 30 msg/s）。压测时用不同用户（CSV 多 line）或调大限流：`-Dwelink.im.send-rate-limit-per-second=300`。

### Q：群成员太多时 OOM

A：JMeter 默认 -Xmx1g。改 `apache-jmeter-5.6.3/bin/jmeter.bat` 把 `set HEAP=-Xms1g -Xmx1g` 改成 `-Xmx4g`。

### Q：怎么看实时指标

A：JMeter `-n` 模式不出 GUI，但每秒会刷新一行：

```
summary +    100 in 00:00:10 = 10.0/s ...
```

或者打开 GUI 直接拖 .jmx 进去手动跑（仅适合 < 100 线程的小测试）。

生产监控用 Prometheus + Grafana 配合 `/actuator/prometheus`。

---

## 推荐执行顺序

1. **第一次跑**：完整数据准备 → Phase 1 全部跑一遍（30 分钟）→ 看基线数字
2. **优化迭代**：每次改后端代码后跑 Phase 1.3（历史拉取）+ Phase 2.3（私聊）做回归
3. **演示亮点**：剧本 A（Kafka 故障恢复）+ 剧本 B（降级），录制视频/截图
4. **长跑稳定性**：Phase 2.2（2000 长连接 30 分钟），夜跑看 JVM 是否稳定

---

## 已知 limitation

- 05 群聊 E2E 投递率受 JMeter `SingleReadWebSocketSampler` 逐帧读取限制，高扇出场景下来不及读取所有帧，实际服务端推送率 61.8%，JMeter 端仅能读到 21.20%
- 没有自动跨实例测试（需要先手动起 instance-2）
- E2E 测试接收者在无消息时会空转，已通过 `Thread.sleep(500)` 缓解但仍有优化空间
