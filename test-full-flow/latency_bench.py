import asyncio, json, time, random, aiohttp, websockets

BASE = "http://localhost:8080"
WS = "ws://localhost:8081/ws"


async def bench(N, sem_send_size):
    tag = random.randint(10000, 99999)
    sem = asyncio.Semaphore(50)

    async with aiohttp.ClientSession() as http:
        async def reg(i):
            u = f"lat_{tag}_{i}"
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
            await ws.send(json.dumps({"type": "auth", "token": u["token"], "deviceId": "lat"}))
            await asyncio.wait_for(ws.recv(), timeout=10)
            return ws
    ws_list = await asyncio.gather(*[conn(u) for u in users])
    ws_map = {users[i]["uid"]: ws_list[i] for i in range(N * 2)}

    send_lat = []
    send_ts = {}

    sem_send = asyncio.Semaphore(sem_send_size)

    async def sender(i):
        s = users[i]
        r = users[N + i]
        ws_s = ws_map[s["uid"]]
        mid = f"b-{i}"
        async with sem_send:
            t0 = time.time()
            await ws_s.send(json.dumps({
                "type": "message", "toUserId": r["uid"],
                "msgId": mid, "content": f"M{i}", "msgType": 1
            }))
            ack = json.loads(await asyncio.wait_for(ws_s.recv(), timeout=10))
            t_ack = time.time()
            if ack.get("status") == "success":
                send_lat.append((t_ack - t0) * 1000)
                send_ts[mid] = i

    t_all_start = time.time()
    await asyncio.gather(*[sender(i) for i in range(N)])
    t_all_sent = time.time()

    await asyncio.sleep(0.5)

    e2e_recv = {}

    async def receiver(i):
        r = users[N + i]
        ws_r = ws_map[r["uid"]]
        mid = f"b-{i}"
        while True:
            try:
                raw = await asyncio.wait_for(ws_r.recv(), timeout=30)
                data = json.loads(raw)
                if data.get("type") == "message" and "msgId" in data:
                    rid = data["msgId"]
                    if rid == mid:
                        e2e_recv[rid] = time.time()
                        return
                elif data.get("type") == "heartbeat":
                    continue
            except asyncio.TimeoutError:
                return

    recv_sem = asyncio.Semaphore(100)
    async def receiver_bounded(i):
        async with recv_sem:
            await receiver(i)

    await asyncio.gather(*[receiver_bounded(i) for i in range(N)])
    t_recv_done = time.time()

    for ws in ws_list:
        await ws.close()

    send_sorted = sorted(send_lat)
    ns = len(send_sorted)

    recv_times = []
    for k, t in e2e_recv.items():
        idx = int(k.split("-")[1])
        recv_times.append(t)
    recv_times.sort()

    ne = len(recv_times)

    send_elapsed = t_all_sent - t_all_start
    burst_qps = N / max(send_elapsed, 0.01)

    print(f"  Pairs={N}  Concurrency={sem_send_size}  SentOK={ns}  RecvOK={ne}")
    if ns > 0:
        print(f"  Send ACK:  avg={sum(send_sorted)/ns:.1f}  min={send_sorted[0]:.1f}  "
              f"p50={send_sorted[ns//2]:.1f}  p95={send_sorted[int(ns*0.95)]:.1f}  "
              f"p99={send_sorted[int(ns*0.99)]:.1f}  max={send_sorted[-1]:.1f} ms")
    print(f"  Burst QPS: {burst_qps:.0f} msg/s  (send duration: {send_elapsed:.1f}s)")
    return ns, ne, send_sorted, burst_qps


async def main():
    print("=" * 65)
    print("  WeLink 端到端延迟压测 (爆发模式)")
    print("=" * 65)

    for N, cc in [(50, 50), (100, 100), (200, 200)]:
        print(f"\n  --- {N} 对并发 {cc} burst ---")
        await bench(N, cc)


asyncio.run(main())
