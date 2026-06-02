import asyncio, json, time, random, aiohttp, websockets

BASE = "http://localhost:8080"
WS = "ws://localhost:8081/ws"


async def batch_paced(batch_size, batch_count, concurrency):
    total = batch_size * batch_count
    tag = random.randint(10000, 99999)
    sem = asyncio.Semaphore(50)

    async with aiohttp.ClientSession() as http:
        async def reg(i):
            u = f"bp_{tag}_{i}"
            async with sem:
                await http.post(f"{BASE}/api/v1/auth/register",
                                json={"username": u, "password": "test123", "nickname": f"U{i}"})
                r = await http.post(f"{BASE}/api/v1/auth/login",
                                    json={"username": u, "password": "test123"})
                d = (await r.json())["data"]
                return {"uid": d["userInfo"]["id"], "token": d["accessToken"]}
        users = await asyncio.gather(*[reg(i) for i in range(total * 2)])

    sem_ws = asyncio.Semaphore(60)
    async def conn(u):
        async with sem_ws:
            ws = await websockets.connect(WS)
            await ws.send(json.dumps({"type": "auth", "token": u["token"], "deviceId": "bp"}))
            await asyncio.wait_for(ws.recv(), timeout=10)
            return ws
    ws_list = await asyncio.gather(*[conn(u) for u in users])

    # Map senders and receivers directly
    sender_ws = [ws_list[i] for i in range(total)]
    receiver_ws = [ws_list[total + i] for i in range(total)]

    all_lat = []
    offset = 0

    for b in range(batch_count):
        N = batch_size
        recv_ts = {}
        lock = asyncio.Lock()

        # Start readers for this batch
        reader_tasks = []
        for i in range(N):
            idx = offset + i
            async def _reader(ws=receiver_ws[idx], expected=f"bp-{idx}"):
                while True:
                    raw = await ws.recv()
                    data = json.loads(raw)
                    if data.get("type") == "message" and data.get("msgId") == expected:
                        async with lock:
                            recv_ts[idx] = time.perf_counter_ns()
                        return
                    elif data.get("type") == "heartbeat":
                        continue
            reader_tasks.append(asyncio.create_task(_reader()))

        await asyncio.sleep(0.2)

        # Send batch
        send_ts = {}
        g = asyncio.Semaphore(concurrency)

        for i in range(N):
            idx = offset + i
            async def _sender(idx=idx, ws=sender_ws[idx], r_uid=users[total + idx]["uid"]):
                mid = f"bp-{idx}"
                async with g:
                    t0 = time.perf_counter_ns()
                    await ws.send(json.dumps({
                        "type": "message", "toUserId": r_uid,
                        "msgId": mid, "content": "M", "msgType": 1
                    }))
                    async with lock:
                        send_ts[idx] = t0
                    await asyncio.wait_for(ws.recv(), timeout=10)
            asyncio.create_task(_sender())

        t_batch = time.perf_counter_ns()
        await asyncio.sleep(3.5)  # wait for all sends + receives

        await asyncio.gather(*reader_tasks, return_exceptions=True)

        batch_lat = []
        for i in range(N):
            idx = offset + i
            if idx in send_ts and idx in recv_ts:
                batch_lat.append((recv_ts[idx] - send_ts[idx]) / 1_000_000)

        batch_lat.sort()
        n = len(batch_lat)
        marker = " (冷启动)" if b == 0 else (" (积压最大)" if b == batch_count - 1 else "")
        if n > 0:
            print(f"  批次{b+1}: {n}/{N}  "
                  f"avg={sum(batch_lat)/n:.0f}ms  p50={batch_lat[n//2]:.0f}ms  "
                  f"p95={batch_lat[int(n*0.95)]:.0f}ms  min={batch_lat[0]:.0f}ms  max={batch_lat[-1]:.0f}ms{marker}")
        else:
            print(f"  批次{b+1}: 0/{N} 丢失!")
        all_lat.extend(batch_lat)
        offset += N

    for ws in ws_list:
        await ws.close()

    s = sorted(all_lat)
    n = len(s)
    print(f"\n  汇总: {n}/{total} msg  "
          f"avg={sum(s)/n:.0f}ms  p50={s[n//2]:.0f}ms  "
          f"p95={s[int(n*0.95)]:.0f}ms  p99={s[int(n*0.99)]:.0f}ms  "
          f"min={s[0]:.0f}ms")

    # 分析退化
    if batch_count >= 3:
        first_avg = sum(all_lat[:N]) / N
        last_start = (batch_count - 1) * N
        last_avg = sum(all_lat[last_start:last_start + N]) / N
        print(f"  第一批 avg={first_avg:.0f}ms → 最后批 avg={last_avg:.0f}ms ({last_avg/first_avg:.1f}x)")

    return s


asyncio.run(batch_paced(50, 8, 50))
