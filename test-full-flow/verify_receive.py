import asyncio, json, time, random, aiohttp, websockets

BASE = "http://localhost:8080"
WS = "ws://localhost:8081/ws"

async def main():
    print("=" * 60)
    print("  WeLink 端到端消息接收验证")
    print("=" * 60)

    tag = random.randint(10000, 99999)
    ua = f"vrfy_a_{tag}"
    ub = f"vrfy_b_{tag}"

    async with aiohttp.ClientSession() as http:
        await http.post(f"{BASE}/api/v1/auth/register",
                        json={"username": ua, "password": "test123", "nickname": "Alice"})
        await http.post(f"{BASE}/api/v1/auth/register",
                        json={"username": ub, "password": "test123", "nickname": "Bob"})
        r_a = await http.post(f"{BASE}/api/v1/auth/login",
                              json={"username": ua, "password": "test123"})
        r_b = await http.post(f"{BASE}/api/v1/auth/login",
                              json={"username": ub, "password": "test123"})
        data_a = await r_a.json()
        data_b = await r_b.json()
        token_a = data_a["data"]["accessToken"]
        token_b = data_b["data"]["accessToken"]
        uid_a = data_a["data"]["userInfo"]["id"]
        uid_b = data_b["data"]["userInfo"]["id"]
        print(f"\n  Alice: id={uid_a}")
        print(f"  Bob:   id={uid_b}")

    ws_a = await websockets.connect(WS)
    ws_b = await websockets.connect(WS)
    await ws_a.send(json.dumps({"type": "auth", "token": token_a, "deviceId": "verify"}))
    await ws_b.send(json.dumps({"type": "auth", "token": token_b, "deviceId": "verify"}))
    resp_a = json.loads(await asyncio.wait_for(ws_a.recv(), timeout=10))
    resp_b = json.loads(await asyncio.wait_for(ws_b.recv(), timeout=10))
    print(f"\n  Auth A: {resp_a['status']}  |  Auth B: {resp_b['status']}")

    print(f"\n  --- ALICE 发送 3 条消息给 Bob ---")
    for i in range(3):
        content = f"你好Bob！第{i+1}条消息 {time.time():.3f}"
        await ws_a.send(json.dumps({
            "type": "message", "toUserId": uid_b,
            "msgId": f"vrfy-{i}-{int(time.time()*1000)}",
            "content": content, "msgType": 1
        }))
        ack = json.loads(await asyncio.wait_for(ws_a.recv(), timeout=10))
        print(f"  [{i+1}] Alice 发出: {content}")

    print(f"\n  --- BOB 实际接收到 ---")
    received = []
    t0 = time.time()
    while len(received) < 3:
        try:
            raw = await asyncio.wait_for(ws_b.recv(), timeout=5)
            data = json.loads(raw)
            if data.get("type") == "message" and "msgId" in data:
                received.append(data)
                print(f"  ✅ 收到: {data['content']}")
            elif data.get("type") == "heartbeat":
                continue
        except asyncio.TimeoutError:
            print(f"  ⚠️  超时: BOB 只收到 {len(received)}/3")
            break

    elapsed = (time.time() - t0) * 1000
    print(f"\n  BOB 接收耗时: {elapsed:.0f}ms")

    await ws_a.close()
    await ws_b.close()

    ok = len(received) == 3
    print(f"\n  {'='*60}")
    print(f"  结论: 发送 3 条 → BOB 收到 {len(received)} 条 → {'✅ 全部收到' if ok else '❌ 有丢失'}")
    print(f"  {'='*60}")

asyncio.run(main())
