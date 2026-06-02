import asyncio, json, time, random, aiohttp, websockets

BASE = "http://localhost:8080"
WS = "ws://localhost:8081/ws"


async def bench_independent(N, rounds):
    """N对独立并发，每对发送 round 条消息，各测各的E2E"""
    tag = random.randint(10000, 99999)
    sem = asyncio.Semaphore(50)

    async with aiohttp.ClientSession() as http:
        async def reg(i):
            u = f"ind_{tag}_{i}"
            async with sem:
                await http.post(f"{BASE}/api/v1/auth/register",
                                json={"username": u, "password": "test123", "nickname": f"U{i}"})
                r = await http.post(f"{BASE}/api/v1/auth/login",
                                    json={"username": u, "password": "test123"})
                d = (await r.json())["data"]
                return {"uid": d["userInfo"]["id"], "token": d["accessToken"]}
        users = await asyncio.gather(*[reg(i) for i in range(N * 2)])

    sem_ws = asyncio.Semaphore(40)
    async def conn(u):
        async with sem_ws:
            ws = await websockets.connect(WS)
            await ws.send(json.dumps({"type": "auth", "token": u["token"], "deviceId": "ind"}))
            await asyncio.wait_for(ws.recv(), timeout=10)
            return ws
    ws_list = await asyncio.gather(*[conn(u) for u in users])
    ws_map = {users[i]["uid"]: ws_list[i] for i in range(N * 2)}

    all_lat = []
    lock = asyncio.Lock()

    async def pair(i):
        s = users[i]
        r = users[N + i]
        ws_s = ws_map[s["uid"]]
        ws_r = ws_map[r["uid"]]

        for ri in range(rounds):
            mid = f"p{i}-{ri}"
            t0 = time.perf_counter()
            await ws_s.send(json.dumps({
                "type": "message", "toUserId": r["uid"],
                "msgId": mid, "content": f"M{ri}", "msgType": 1
            }))
            await asyncio.wait_for(ws_s.recv(), timeout=10)

            while True:
                raw = await asyncio.wait_for(ws_r.recv(), timeout=15)
                data = json.loads(raw)
                if data.get("type") == "message" and data.get("msgId") == mid:
                    t1 = time.perf_counter()
                    async with lock:
                        all_lat.append((t1 - t0) * 1000)
                    break
                elif data.get("type") == "heartbeat":
                    continue

            await asyncio.sleep(0.02)

    sem_g = asyncio.Semaphore(N)
    async def pair_bounded(i):
        async with sem_g:
            await pair(i)

    t0 = time.perf_counter()
    await asyncio.gather(*[pair_bounded(i) for i in range(N)])
    dur = time.perf_counter() - t0

    for ws in ws_list:
        await ws.close()

    s = sorted(all_lat)
    n = len(s)
    total = N * rounds
    print(f"  {N}对 x {rounds}轮 = {total}条  耗时={dur:.1f}s  "
          f"Recv={n}/{total}  QPS={total/dur:.0f}")

    if n > 0:
        print(f"     avg={sum(s)/n:.0f}ms  p50={s[n//2]:.0f}ms  "
              f"p95={s[int(n*0.95)]:.0f}ms  p99={s[int(n*0.99)]:.0f}ms  "
              f"min={s[0]:.0f}ms  max={s[-1]:.0f}ms")


async def main():
    print("=" * 70)
    print("  E2E: N对并发 × 各独立计时 (无event loop排队干扰)")
    print("=" * 70)

    for N, rounds in [(5, 10), (10, 10)]:
        print(f"\n  --- {N} 对并发 x {rounds} 轮 ---")
        await bench_independent(N, rounds)


asyncio.run(main())
