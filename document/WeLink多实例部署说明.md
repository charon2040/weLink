# WeLink 多实例部署说明

## 1. 文档目的

本文档专门解释 WeLink 应用实例如何横向扩展，以及多个实例之间如何完成私聊、群聊和在线状态同步。

## 2. 多实例为什么能工作

每个实例都具备完整能力：

- HTTP API
- WebSocket 服务
- Kafka 消费者
- Outbox 发布调度器

跨实例协调依赖四类共享设施：

- Redis：在线路由与聚合路由
- Kafka：跨实例消息广播
- MySQL：消息持久化
- MinIO：文件元数据与对象统一存储

## 3. 关键机制

### 3.1 在线路由

用户鉴权成功后，实例会写入 Redis：

- 单设备路由键
- 用户聚合路由键

这样发送端实例就能判断目标用户：

- 是否在线
- 在线在哪个实例
- 是否需要走 Kafka 跨实例投递

### 3.2 私聊跨实例

流程：

1. 发送方实例落主消息
2. 若接收者在线且在其他实例，写 `message_outbox`
3. 调度器把待发布记录发到 Kafka
4. 目标实例消费消息并推送给本地在线 session

### 3.3 群聊跨实例

流程：

1. 发送方实例拿到群成员列表
2. 把本地在线、远端在线、离线成员拆开
3. 本地在线直接推送
4. 远端在线写 `message_outbox`
5. 离线成员写 `message_inbox`

当前实现已经把 `message_outbox` 按 `target_user_id % 64` 做分桶批量写入。

## 4. 本地启动第二个实例

示例：

```bash
cd g:/WeLink
java -jar target/WeLink-0.0.1-SNAPSHOT.jar --server.port=18080 --welink.websocket.port=18081 --welink.instance.id=instance-2
```

建议参数约束：

- `server.port` 不与实例 1 冲突
- `welink.websocket.port` 不与实例 1 冲突
- `welink.instance.id` 必须唯一

## 5. 用户到底进入哪个实例

不是注册时决定，也不是用户 ID 决定。

真正决定“这个用户现在挂在哪个实例上”的时机，是 **WebSocket 建连并完成 `auth` 鉴权时**。

### 5.1 注册阶段

注册只是一次普通 HTTP 请求：

- 可以落到任意一个应用实例
- 只要各实例共享同一套 MySQL，注册结果就一致
- 注册完成后不会把用户固定到某个实例

### 5.2 登录阶段

登录也是普通 HTTP 请求：

- 也可以落到任意一个应用实例
- JWT 是无状态的，所以登录在哪个实例并不重要
- 登录返回 token 后，真正的“实例归属”还没有确定

### 5.3 归属决定阶段

用户打开 WebSocket 并发送 `auth` 帧后：

1. 当前连上的实例完成 JWT 校验
2. 该实例把路由写入 Redis
3. 从这时开始，系统才认为这个用户在线在该实例上

因此，多实例场景下，决定用户进入哪个实例的关键不是“注册/登录”，而是：

- WebSocket 连接打到了哪个实例
- 或者说，你的负载均衡/入口层把这次长连接分配给了哪个实例

### 5.4 当前仓库的实际情况

当前仓库已经支持两种 WebSocket 入口方式：

- 若设置 `frontend/.env` 中的 `VITE_WS_URL`，前端优先使用该地址
- 若未设置 `VITE_WS_URL`，前端会按当前页面域名自动推导 `ws://当前域名/ws` 或 `wss://当前域名/ws`

因此：

- 同域部署时，前端可以直接统一连接 `/ws`
- 前后端分域部署时，可以显式指定 `VITE_WS_URL=wss://im.example.com/ws`
- 本地 Vite 开发态也已代理 `/ws`，不需要再把前端代码写死到某个实例端口

### 5.5 想让用户分散到不同实例，有两种常见方式

方式 1：手工指定不同入口

- 一部分客户端连 `instance-1` 的 WebSocket 端口
- 另一部分客户端连 `instance-2` 的 WebSocket 端口

方式 2：前面放负载均衡

- 前端统一连一个域名
- 由 Nginx / LB 把不同长连接分发到不同实例
- 建连成功后，该连接就固定归属于被分配到的那个实例

推荐优先使用方式 2，因为它更符合真实线上形态。

### 5.6 推荐落地方式

前端建议：

- 同域部署时，不配置 `VITE_WS_URL`
- 前端页面通过 `https://im.example.com` 打开后，WebSocket 会自动连到 `wss://im.example.com/ws`
- 前端开发态可参考 `frontend/.env.example`

负载均衡建议：

- HTTP `/api` 走 `least_conn`
- WebSocket `/ws` 也走独立 upstream
- 单条 WebSocket 长连接在握手成功后会固定落在某个后端实例，不会在连接存活期间漂移
- K8s 场景下，`k8s/nginx-configmap.yaml` 默认走仓库里已有的 `im-gateway` Service
- 本地双实例联调可直接参考 `k8s/nginx-local-multi-instance.conf.example`，它按 `8080/18080` 和 `8081/18081` 分发

仓库中可直接参考：

- `frontend/src/utils/websocket.js`
- `frontend/vite.config.js`
- `k8s/nginx-configmap.yaml`
- `k8s/nginx-local-multi-instance.conf.example`

## 6. 多实例压测方法

### 5.1 准备思路

要验证真实跨实例，不能只启动第二个进程，必须让同一个群里的用户分布到两个实例：

- 一批在线成员连接到 `instance-1`
- 一批在线成员连接到 `instance-2`
- 发送者与远端接收者不在同一实例

### 5.2 验证点

至少同时验证三件事：

- Redis 路由键显示不同用户落在不同实例
- 发送端压测成功
- 目标实例日志中出现对应消费事件

### 5.3 当前已验证结果

当前仓库已经实测通过：

- 第二个 IM 实例本地拉起
- 真实跨实例群聊压测
- `message_outbox` 分桶批量写收益落地

## 7. 常见错误

### 6.1 两个实例用了同一个 `instance.id`

后果：

- 路由表互相覆盖
- Kafka consumer group 语义错乱
- 难以判断消息实际落在哪个实例

### 6.2 两个实例没有共享同一套 Redis/Kafka

后果：

- 路由不可见
- 跨实例消息无法广播到目标实例

### 6.3 只启动第二个实例但没有把用户分散连过去

后果：

- 压测虽然看起来是双实例，实际上 `otherInstance=0`
- 测不到 outbox 路径

## 8. 运维建议

- 保持 `welink.instance.id` 与主机/Pod 名一致
- 为每个实例暴露独立 `/actuator/health` 与 `/actuator/prometheus`
- 压测时单独记录每个实例日志，方便判断跨实例是否命中
- 当需要停止实例时先停止流量入口，再停进程，避免新连接落到即将退出的实例

## 9. 参考文档

- `document/WeLink分布式部署指南.md`
- `document/分布式压测部署指南.md`
- `document/并发性能测试设计文档.md`
- `document/消息链路文档.md`
