import asyncio, json, time, random, aiohttp, websockets

BASE = "http://localhost:8080"
WS = "ws://localhost:8081/ws"


async def bench(N, send_cc):
    tag = random.randint(10000, 99999)
    sem = asyncio.Semaphore(50)

    async with aiohttp.ClientSession() as http:
        async def reg(i):
            u = f"e4_{tag}_{i}"
            async with sem:
                await http.post(f"{BASE}/api/v1/auth/register",
                                json={"username": u, "password": "test123", "nickname": f"U{i}"})
                r = await http.post(f"{BASE}/api/v1/auth/login",
                                    json={"username": u, "password": "test123"})
                d = (await r.json())["data"]
                return {"uid": d["userInfo"]["id"], "token": d["accessToken"]}
        users = await asyncio.gather(*[reg(i) for i in range(N * 2)])

    sem_ws = asyncio.Semaphore(60)
    async def conn(u):
        async with sem_ws:
            ws = await websockets.connect(WS)
            await ws.send(json.dumps({"type": "auth", "token": u["token"], "deviceId": "e4"}))
            await asyncio.wait_for(ws.recv(), timeout=10)
            return ws
    ws_list = await asyncio.gather(*[conn(u) for u in users])
    ws_map = {users[i]["uid"]: ws_list[i] for i in range(N * 2)}

    e2e_lat = []
    lock = asyncio.Lock()

    # 关键: pair里不取ACK，用独立的两阶段: 全部发送 → 全部接收
    # 发送阶段: 只发消息，不等ACK
    send_ts = {}
    send_done = asyncio.Event()
    sem_s = asyncio.Semaphore(send_cc)

    async def sender_only(i):
        s = users[i]
        r = users[N + i]
        ws_s = ws_map[s["uid"]]
        mid = f"b-{i}"
        async with sem_s:
            t0 = time.perf_counter()
            await ws_s.send(json.dumps({
                "type": "message", "toUserId": r["uid"],
                "msgId": mid, "content": f"M{i}", "msgType": 1
            }))
            async with lock:
                send_ts[mid] = t0
            # 清ACK
            await asyncio.wait_for(ws_s.recv(), timeout=10)

    t_burst = time.perf_counter()
    await asyncio.gather(*[sender_only(i) for i in range(N)])
    send_dur = time.perf_counter() - t_burst
    send_done.set()

    # 接收阶段: N个接收方并发收
    recv_ts = {}

    async def receiver_only(i):
        r = users[N + i]
        ws_r = ws_map[r["uid"]]
        mid = f"b-{i}"
        while True:
            try:
                raw = await ws_r.recv()
                data = json.loads(raw)
                if data.get("type") == "message" and data.get("msgId") == mid:
                    async with lock:
                        recv_ts[mid] = time.perf_counter()
                    return
                elif data.get("type") == "heartbeat":
                    continue
            except Exception:
                return

    t_recv_start = time.perf_counter()
    await asyncio.gather(*[receiver_only(i) for i in range(N)])
    recv_dur = time.perf_counter() - t_recv_start

    for ws in ws_list:
        await ws.close()

    # 计算每条消息的精确E2E
    for mid, t_s in send_ts.items():
        t_r = recv_ts.get(mid)
        if t_r:
            e2e_lat.append((t_r - t_s) * 1000)

    s = sorted(e2e_lat)
    n = len(s)
    burst_qps = N / max(send_dur, 0.001)

    print(f"  N={N:<4} cc={send_cc}  sent={send_dur*1000:.0f}ms  "
          f"drain={recv_dur*1000:.0f}ms  Recv={n}/{N}  "
          f"BurstQPS={burst_qps:.0f}")

    if n > 0:
        print(f"     avg={sum(s)/n:.0f}ms  p50={s[n//2]:.0f}ms  "
              f"p95={s[int(n*0.95)]:.0f}ms  p99={s[int(n*0.99)]:.0f}ms  "
              f"min={s[0]:.0f}ms  max={s[-1]:.0f}ms")


async def main():
    print("=" * 75)
    print("  E2E: 发送发出时间(perf_counter) → 接收方收到时间(perf_counter)")
    print("  Phase1: burst发送+清ACK | Phase2: 全部接收方并发收")
    print("=" * 75)

    for N, cc in [(20, 20), (50, 50), (100, 100), (200, 100)]:
        print(f"\n  --- {N} 对 ---")
        await bench(N, cc)


asyncio.run(main())
