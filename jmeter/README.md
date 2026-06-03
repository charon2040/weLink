# WeLink JMeter 压测套件

> 当前仓库的有效压测套件是 `jmeter/plans/01-09` 共 9 个 JMX 场景。本文档只描述这套实际在用的方案。

## 2026-06-03 当前回归结论

| Plan | 最新结果文件 | 关键结果 | 备注 |
|------|--------------|----------|------|
| 01 连接容量 | `01-connection-capacity_20260603_184237` | 5,000 WS 连接，0 错误 | 连接容量通过 |
| 02 私聊吞吐 | `02-private-throughput_20260603_202345` | 922,887 条发送，10,279.89 msg/s，0 错误 | 私聊入口 10K 达成 |
| 03 单群入口 | `03-group-throughput_20260603_184640` | 8,150 条发送，91.20 group msg/s，0 错误 | 100 人单群入口基线 |
| 04 私聊 E2E | `04-private-e2e_20260603_223520` | 576,273 条发送，7,191.37 msg/s；发送 0 错误；接收 OK 181,864；后端计数 576,273，Kafka lag=0 | 单机私聊 E2E 7K 达成 |
| 05 单群 E2E | `05-group-e2e_20260603_222839` | 6,926 条发送，87.32 group msg/s；接收 OK 451,891；Kafka lag=0，fanout queue=0 | fanout bucket 优化后无后端积压 |
| 06 混合入口 | `06-mixed-scenario_20260603_185355` | 私聊 4,773.76 msg/s + 群聊 88.59 group msg/s | 总错误率约 0.0009% |
| 07 多群入口 | `07-multi-group-throughput_20260603_185540` | 25,273 条发送，283.26 group msg/s | 只发不收，不代表 fanout 完成 |
| 08 混合 E2E | `08-mixed-e2e_20260603_185720` | 私聊发送 357,564；群聊发送 3,702；总错误率 0.073% | 接收端开始成为瓶颈 |
| 09 多群 E2E | `09-multi-group-e2e_20260603_223156` | 11,113 条发送，140.08 group msg/s；接收 OK 407,598；Kafka lag=0，fanout queue=0 | 16x100 多群后端无积压 |

多群口径注意：

- `multi-group-members.csv` 必须按 `MemberIndex, GroupIndex` 交错分布。
- 不要使用 group-major CSV，否则前 N 个线程会集中打少数 groupId。
- `07` 只验证入口吞吐和 lag；在线投递看 `09`。

## 目录结构

```text
jmeter/
├── README.md
├── plans/
│   ├── 01-connection-capacity.jmx
│   ├── 02-private-throughput.jmx
│   ├── 03-group-throughput.jmx
│   ├── 04-private-e2e.jmx
│   ├── 05-group-e2e.jmx
│   ├── 06-mixed-scenario.jmx
│   ├── 07-multi-group-throughput.jmx
│   ├── 08-mixed-e2e.jmx
│   └── 09-multi-group-e2e.jmx
├── data/
├── results/
├── reports/
└── scripts/
```

## 场景说明

| 场景 | JMX | 目标 | 说明 |
|------|-----|------|------|
| 01 | `01-connection-capacity.jmx` | WebSocket 连接容量 | 只看建连、认证和连接保持 |
| 02 | `02-private-throughput.jmx` | 私聊入口吞吐 | 只发不收 |
| 03 | `03-group-throughput.jmx` | 单群入口吞吐 | 单热群入口能力 |
| 04 | `04-private-e2e.jmx` | 私聊 E2E | 发送到接收完整链路 |
| 05 | `05-group-e2e.jmx` | 单群 E2E | 单热群完整链路 |
| 06 | `06-mixed-scenario.jmx` | 混合入口 | 私聊 + 单群混合入口 |
| 07 | `07-multi-group-throughput.jmx` | 多群入口吞吐 | 16x100，多群只发不收 |
| 08 | `08-mixed-e2e.jmx` | 混合 E2E | 私聊 + 单群完整链路 |
| 09 | `09-multi-group-e2e.jmx` | 多群 E2E | 16x100 多群完整链路 |

## 前置准备

### 1. 安装 WebSocket 插件

JMeter 默认不支持 WebSocket，需要安装 `JMeter WebSocket Samplers by Peter Doornbosch`。

### 2. 启动服务

```powershell
docker compose -p welink-main up -d
java -jar target\WeLink-0.0.1-SNAPSHOT.jar
```

健康检查：

```powershell
curl http://localhost:8080/actuator/health
```

### 3. 准备数据

推荐直接使用：

```powershell
pwsh jmeter\scripts\prepare-all.ps1
```

当前默认准备口径：

- 25,000 账号
- 5,000 对私聊
- 单群 151 人
- 多群 16 x 100

每次做 E2E 回归前，建议重新生成：

```powershell
pwsh jmeter\scripts\prepare-e2e.ps1 -PvtSenders 500 -PvtReceivers 200 -GrpSenders 50 -GrpReceivers 100
pwsh jmeter\scripts\prepare-multi-group.ps1 -GroupCount 16 -MembersPerGroup 100
```

## 执行方式

### 1. 一键跑完整套件

```powershell
pwsh jmeter\scripts\stress.ps1
```

`stress.ps1` 会按顺序执行 `01-09`，并使用当前推荐参数。

### 2. 单独跑某个场景

```powershell
pwsh jmeter\scripts\run.ps1 -Plan 04-private-e2e -Threads 700 -Duration 90 -Ramp 20 -ExtraArgs "-JsendThreads=500 -JrecvThreads=200 -JsendDelayMs=50 -JwarmupMs=10000 -JrecvDuration=120 -JdrainSec=30 -JrecvReadTimeout=3000"
```

### 3. 常见单场景命令

```powershell
pwsh jmeter\scripts\run.ps1 -Plan 01-connection-capacity -Threads 5000 -Duration 90 -Ramp 30
pwsh jmeter\scripts\run.ps1 -Plan 02-private-throughput -Threads 500 -Duration 90 -Ramp 20 -ExtraArgs "-JdelayMs=40"
pwsh jmeter\scripts\run.ps1 -Plan 03-group-throughput -Threads 50 -Duration 90 -Ramp 15 -ExtraArgs "-JdelayMs=500"
pwsh jmeter\scripts\run.ps1 -Plan 05-group-e2e -Threads 150 -Duration 90 -Ramp 20 -ExtraArgs "-JsendThreads=50 -JrecvThreads=100 -JsendDelayMs=500 -JwarmupMs=10000 -JrecvDuration=120 -JdrainSec=30 -JrecvReadTimeout=3000"
pwsh jmeter\scripts\run.ps1 -Plan 07-multi-group-throughput -Threads 160 -Duration 90 -Ramp 20 -ExtraArgs "-JdelayMs=500"
pwsh jmeter\scripts\run.ps1 -Plan 09-multi-group-e2e -Threads 960 -Duration 90 -Ramp 20 -ExtraArgs "-JsendThreads=160 -JrecvThreads=800 -JsendDelayMs=500 -JwarmupMs=10000 -JrecvDuration=120 -JdrainSec=30 -JsendRamp=20 -JrecvRamp=20 -JrecvReadTimeout=3000"
```

## 结果判读

每次压测都要同时看三类结果：

- JMeter JTL / HTML 报告
- `/actuator/prometheus` 指标
- Kafka consumer group lag

最小检查集：

- 私聊：`welink_message_sent_messages_total{type="private"}`
- 群聊：`welink_message_sent_messages_total{type="large_group"}` 或 `type="group"`
- 队列：`welink_message_persist_queue_size_items`、`welink_async_batch_queue_size_items`、`welink_group_fanout_queue_size_tasks`
- Kafka：`welink-im-private-ingress`、`welink-im-group-ingress` 的 lag

## 当前结论口径

- 私聊入口：看 `02`
- 私聊 E2E：看 `04`
- 单群入口：看 `03`
- 单群 E2E：看 `05`
- 多群入口：看 `07`
- 多群 E2E：看 `09`
- 混合入口：看 `06`
- 混合 E2E：看 `08`

不要再用旧版“6 个测试场景”或旧版 HTTP 注册/登录/ACK/长连接方案描述当前套件。

## 已知限制

- JMeter `SingleReadWebSocketSampler` 在高 fanout 场景下接收效率不足。
- `07` 入口吞吐不能代表完整在线投递能力。
- 单热群性能必须同时看 `group msg/s`、Kafka lag、fanout queue，不能只看发送端。

## 相关文件

- 当前结果摘要：[performance-report.md](G:/WeLink/jmeter/results/performance-report.md)
- 测试报告：[测试报告.md](G:/WeLink/document/测试报告.md)
