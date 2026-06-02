import asyncio, json, time, random, aiohttp, websockets

BASE = "http://localhost:8080"
WS = "ws://localhost:8081/ws"


async def single_pair_latency(rounds=50):
    """单对用户反复发收消息，每次独立计时，测量精确E2E"""
    tag = random.randint(10000, 99999)
    ua = f"sp_a_{tag}"
    ub = f"sp_b_{tag}"

    async with aiohttp.ClientSession() as http:
        await http.post(f"{BASE}/api/v1/auth/register",
                        json={"username": ua, "password": "test123", "nickname": "A"})
        await http.post(f"{BASE}/api/v1/auth/register",
                        json={"username": ub, "password": "test123", "nickname": "B"})
        ra = await http.post(f"{BASE}/api/v1/auth/login",
                             json={"username": ua, "password": "test123"})
        rb = await http.post(f"{BASE}/api/v1/auth/login",
                             json={"username": ub, "password": "test123"})
        da = (await ra.json())["data"]
        db = (await rb.json())["data"]
        uid_a = da["userInfo"]["id"]
        uid_b = db["userInfo"]["id"]
        tok_a = da["accessToken"]
        tok_b = db["accessToken"]

    ws_a = await websockets.connect(WS)
    ws_b = await websockets.connect(WS)
    await ws_a.send(json.dumps({"type": "auth", "token": tok_a, "deviceId": "sp"}))
    await ws_b.send(json.dumps({"type": "auth", "token": tok_b, "deviceId": "sp"}))
    await asyncio.wait_for(ws_a.recv(), timeout=10)
    await asyncio.wait_for(ws_b.recv(), timeout=10)

    latencies = []
    for r in range(rounds):
        mid = f"sp-{r}"
        content = f"msg-{r}"

        # 计时: ws_send 完成 → ws_b 收到
        t_send = time.perf_counter()
        await ws_a.send(json.dumps({
            "type": "message", "toUserId": uid_b,
            "msgId": mid, "content": content, "msgType": 1
        }))
        await asyncio.wait_for(ws_a.recv(), timeout=10)  # 清ACK

        while True:
            raw = await asyncio.wait_for(ws_b.recv(), timeout=15)
            data = json.loads(raw)
            if data.get("type") == "message" and data.get("msgId") == mid:
                t_recv = time.perf_counter()
                lat = (t_recv - t_send) * 1000
                latencies.append(lat)
                break
            elif data.get("type") == "heartbeat":
                continue

        await asyncio.sleep(0.05)  # 间隔50ms防限流

    await ws_a.close()
    await ws_b.close()

    s = sorted(latencies)
    n = len(s)
    print(f"  单对消息 发送→对方收到 ({n} 次)")
    print(f"  avg={sum(s)/n:.0f}ms  p50={s[n//2]:.0f}ms  p95={s[int(n*0.95)]:.0f}ms  "
          f"p99={s[int(n*0.99)]:.0f}ms  min={s[0]:.0f}ms  max={s[-1]:.0f}ms")


async def main():
    print("=" * 65)
    print("  精确E2E: 1对用户反复发收 (无并发干扰)")
    print("=" * 65)

    for _ in range(3):
        print()
        await single_pair_latency(50)

asyncio.run(main())
