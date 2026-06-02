import asyncio, json, time, random, aiohttp, websockets

BASE = "http://localhost:8080"
WS = "ws://localhost:8081/ws"


async def run(N, concurrency):
    tag = random.randint(10000, 99999)
    sem = asyncio.Semaphore(50)

    async with aiohttp.ClientSession() as http:
        async def reg(i):
            u = f"h4_{tag}_{i}"
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
            await ws.send(json.dumps({"type": "auth", "token": u["token"], "deviceId": "h4"}))
            await asyncio.wait_for(ws.recv(), timeout=10)
            return ws
    ws_list = await asyncio.gather(*[conn(u) for u in users])
    ws_map = {users[i]["uid"]: ws_list[i] for i in range(N * 2)}

    send_ts = {}
    recv_ts = {}
    lock = asyncio.Lock()

    # 接收方后台 reader
    async def reader(ws, mid):
        while True:
            raw = await ws.recv()
            data = json.loads(raw)
            if data.get("type") == "message" and data.get("msgId") == mid:
                async with lock:
                    recv_ts[mid] = time.perf_counter_ns()
                return
            elif data.get("type") == "heartbeat":
                continue

    reader_tasks = []
    for i in range(N):
        mid = f"h4-{i}"
        r_ws = ws_map[users[N + i]["uid"]]
        reader_tasks.append(asyncio.create_task(reader(r_ws, mid)))

    await asyncio.sleep(0.3)

    # 发送方爆发
    g = asyncio.Semaphore(concurrency)

    async def sender(i):
        ws_s = ws_map[users[i]["uid"]]
        mid = f"h4-{i}"
        r_uid = users[N + i]["uid"]
        async with g:
            t0 = time.perf_counter_ns()
            await ws_s.send(json.dumps({
                "type": "message", "toUserId": r_uid,
                "msgId": mid, "content": "M", "msgType": 1
            }))
            async with lock:
                send_ts[mid] = t0
            await asyncio.wait_for(ws_s.recv(), timeout=10)

    blast_start = time.perf_counter_ns()
    await asyncio.gather(*[sender(i) for i in range(N)])
    send_ms = (time.perf_counter_ns() - blast_start) / 1_000_000

    await asyncio.wait_for(asyncio.gather(*reader_tasks, return_exceptions=True), timeout=120)

    for ws in ws_list:
        await ws.close()

    lat = []
    for mid, t0 in send_ts.items():
        t1 = recv_ts.get(mid)
        if t1 is not None:
            lat.append((t1 - t0) / 1_000_000)

    s = sorted(lat)
    n = len(s)
    qps = N / max(send_ms / 1000, 0.001)

    print(f"  {N:>4}对  send={send_ms:.0f}ms  Recv={n}/{N}  BurstQPS={qps:.0f}")
    if n > 0:
        print(f"  E2E: avg={sum(s)/n:.0f}ms  "
              f"p50={s[n//2]:.0f}ms  p95={s[int(n*0.95)]:.0f}ms  "
              f"p99={s[int(n*0.99)]:.0f}ms  min={s[0]:.0f}ms  max={s[-1]:.0f}ms")
        print(f"       [min=无竞争真实路径, p50+=asyncio调度排队]")


async def main():
    print()
    print("=" * 65)
    print("  E2E 高并发: 发送 ws.send → 接收 ws.recv")
    print("=" * 65)
    for N, cc in [(20, 20), (50, 50), (100, 100), (200, 200), (400, 300)]:
        print(f"\n  --- {N} 对 ---")
        await run(N, cc)
    print()

asyncio.run(main())
