import asyncio, json, time, random, aiohttp, websockets

BASE = "http://localhost:8080"
WS = "ws://localhost:8081/ws"


async def bench(N, rounds, msg_size):
    tag = random.randint(10000, 99999)
    sem = asyncio.Semaphore(50)
    payload = "x" * msg_size

    async with aiohttp.ClientSession() as http:
        async def reg(i):
            u = f"cs{tag}_{i}"
            async with sem:
                await http.post(f"{BASE}/api/v1/auth/register",
                                json={"username": u, "password": "test123", "nickname": f"U{i}"})
                r = await http.post(f"{BASE}/api/v1/auth/login",
                                    json={"username": u, "password": "test123"})
                d = (await r.json())["data"]
                return {"uid": d["userInfo"]["id"], "token": d["accessToken"]}
        users = await asyncio.gather(*[reg(i) for i in range(N * 2)])

    sem_ws = asyncio.Semaphore(80)
    async def conn(u):
        async with sem_ws:
            ws = await websockets.connect(WS)
            await ws.send(json.dumps({"type": "auth", "token": u["token"], "deviceId": "cs"}))
            await asyncio.wait_for(ws.recv(), timeout=10)
            return ws
    ws_list = await asyncio.gather(*[conn(u) for u in users])

    all_lat = []
    lock = asyncio.Lock()

    async def pair(idx):
        ws_s = ws_list[idx]
        ws_r = ws_list[N + idx]
        r_uid = users[N + idx]["uid"]

        for ri in range(rounds):
            mid = f"c{idx}r{ri}"
            t0 = time.perf_counter_ns()
            await ws_s.send(json.dumps({
                "type": "message", "toUserId": r_uid,
                "msgId": mid, "content": payload, "msgType": 1
            }))
            await asyncio.wait_for(ws_s.recv(), timeout=10)
            while True:
                raw = await asyncio.wait_for(ws_r.recv(), timeout=30)
                data = json.loads(raw)
                if data.get("type") == "message" and data.get("msgId") == mid:
                    t1 = time.perf_counter_ns()
                    async with lock:
                        all_lat.append((t1 - t0) / 1_000_000)
                    break
                elif data.get("type") == "heartbeat":
                    continue
            await asyncio.sleep(0.005)

    g = asyncio.Semaphore(N)
    async def pair_bounded(i):
        async with g:
            await pair(i)

    t_start = time.perf_counter()
    await asyncio.gather(*[pair_bounded(i) for i in range(N)])
    dur = time.perf_counter() - t_start

    for ws in ws_list:
        await ws.close()

    s = sorted(all_lat)
    n = len(s)
    total = N * rounds
    return dict(n=n, total=total, dur=dur, qps=total / dur,
                avg=sum(s) / n if n else 0, p50=s[n // 2] if n else 0,
                p95=s[int(n * 0.95)] if n else 0, p99=s[int(n * 0.99)] if n else 0,
                min=s[0] if n else 0, max=s[-1] if n else 0)


async def main():
    print("=" * 80)
    print("  E2E latency vs concurrency x 消息大小 (10KB固定)")
    print("=" * 80)
    header = f"  {'并发':>6} {'条数':>6} {'p50':>6} {'p95':>6} {'p99':>6} {'min':>6} {'max':>6} {'QPS':>6}"
    print(header)
    print("  " + "-" * 54)

    for N in [1, 5, 10, 20, 50, 100]:
        rounds = max(3, 30 // N)
        r = await bench(N, rounds, 10240)
        print(f"  {N:>6} {r['n']:>6} {r['p50']:>5.0f}ms {r['p95']:>5.0f}ms {r['p99']:>5.0f}ms "
              f"{r['min']:>5.0f}ms {r['max']:>5.0f}ms {r['qps']:>5.0f}")
        await asyncio.sleep(0.3)

    print()
    print("  " + "=" * 80)
    print("  E2E latency vs concurrency x 消息大小 (1KB)")  
    print("  " + "=" * 80)
    print(header)
    print("  " + "-" * 54)

    for N in [1, 5, 10, 20, 50, 100]:
        rounds = max(3, 30 // N)
        r = await bench(N, rounds, 1024)
        print(f"  {N:>6} {r['n']:>6} {r['p50']:>5.0f}ms {r['p95']:>5.0f}ms {r['p99']:>5.0f}ms "
              f"{r['min']:>5.0f}ms {r['max']:>5.0f}ms {r['qps']:>5.0f}")
        await asyncio.sleep(0.3)

    print()


asyncio.run(main())
