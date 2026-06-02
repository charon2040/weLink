import asyncio, json, time, random, aiohttp, websockets

BASE = "http://localhost:8080"
WS = "ws://localhost:8081/ws"


async def test_size(size, rounds):
    tag = random.randint(10000, 99999)
    ua = f"sz{tag}a"
    ub = f"sz{tag}b"
    payload = "x" * size

    async with aiohttp.ClientSession() as http:
        await http.post(f"{BASE}/api/v1/auth/register",
                        json={"username": ua, "password": "test123", "nickname": "A"})
        await http.post(f"{BASE}/api/v1/auth/register",
                        json={"username": ub, "password": "test123", "nickname": "B"})
        r_a = await http.post(f"{BASE}/api/v1/auth/login",
                              json={"username": ua, "password": "test123"})
        r_b = await http.post(f"{BASE}/api/v1/auth/login",
                              json={"username": ub, "password": "test123"})
        token_a = (await r_a.json())["data"]["accessToken"]
        data_b = await r_b.json()
        token_b = data_b["data"]["accessToken"]
        uid_b = data_b["data"]["userInfo"]["id"]

    ws_a = await websockets.connect(WS)
    ws_b = await websockets.connect(WS)
    await ws_a.send(json.dumps({"type": "auth", "token": token_a, "deviceId": "sz"}))
    await ws_b.send(json.dumps({"type": "auth", "token": token_b, "deviceId": "sz"}))
    await asyncio.wait_for(ws_a.recv(), timeout=10)
    await asyncio.wait_for(ws_b.recv(), timeout=10)

    latencies = []
    for r in range(rounds):
        mid = f"sz-{r}"
        t0 = time.perf_counter_ns()
        await ws_a.send(json.dumps({
            "type": "message", "toUserId": uid_b,
            "msgId": mid, "content": payload, "msgType": 1
        }))
        await asyncio.wait_for(ws_a.recv(), timeout=10)
        while True:
            raw = await asyncio.wait_for(ws_b.recv(), timeout=15)
            data = json.loads(raw)
            if data.get("type") == "message" and data.get("msgId") == mid:
                latencies.append((time.perf_counter_ns() - t0) / 1_000_000)
                break
            elif data.get("type") == "heartbeat":
                continue
        await asyncio.sleep(0.01)

    await ws_a.close()
    await ws_b.close()
    s = sorted(latencies)
    n = len(s)
    return dict(avg=sum(s) / n, p50=s[n // 2], p95=s[int(n * 0.95)],
                p99=s[int(n * 0.99)], min=s[0], max=s[-1], n=n)


async def main():
    print()
    print(f"{'Size':>8}  {'avg':>6}  {'p50':>6}  {'p95':>6}  {'p99':>6}  {'min':>6}  {'max':>6}")
    for size in [8, 256, 1024, 4096, 16384, 32768]:
        r = await test_size(size, 40)
        label = f"{size}B" if size < 1024 else f"{size // 1024}KB"
        print(f"{label:>8}  {r['avg']:>5.0f}ms  {r['p50']:>5.0f}ms  {r['p95']:>5.0f}ms  "
              f"{r['p99']:>5.0f}ms  {r['min']:>5.0f}ms  {r['max']:>5.0f}ms")
        await asyncio.sleep(0.3)
    print()


asyncio.run(main())
