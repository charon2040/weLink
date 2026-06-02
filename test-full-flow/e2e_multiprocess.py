import asyncio, json, time, random, aiohttp, websockets, multiprocessing, sys

BASE = "http://localhost:8080"
WS = "ws://localhost:8081/ws"


def worker(worker_id, pairs_per_process, queue):
    asyncio.run(_worker(worker_id, pairs_per_process, queue))


async def _worker(wid, N, queue):
    tag = random.randint(10000, 99999)
    sem = asyncio.Semaphore(30)

    async with aiohttp.ClientSession() as http:
        async def reg(i):
            u = f"mp{wid}_{tag}_{i}"
            async with sem:
                for _ in range(10):
                    await http.post(f"{BASE}/api/v1/auth/register",
                                    json={"username": u, "password": "test123", "nickname": f"W{wid}"})
                    r = await http.post(f"{BASE}/api/v1/auth/login",
                                        json={"username": u, "password": "test123"})
                    d = (await r.json()).get("data")
                    if d and d.get("accessToken"):
                        return {"uid": d["userInfo"]["id"], "token": d["accessToken"]}
                    await asyncio.sleep(1)
                return None
        users_raw = await asyncio.gather(*[reg(i) for i in range(N * 2)])
        users = [u for u in users_raw if u is not None]
        if len(users) < N * 2:
            N = len(users) // 2
            users = users[:N * 2]
        if N == 0:
            queue.put((wid, []))
            return

    sem_ws = asyncio.Semaphore(15)
    async def conn(u):
        async with sem_ws:
            ws = await websockets.connect(WS)
            await ws.send(json.dumps({"type": "auth", "token": u["token"], "deviceId": f"mp-{wid}"}))
            await asyncio.wait_for(ws.recv(), timeout=10)
            return ws
    ws_list = await asyncio.gather(*[conn(u) for u in users])

    send_ts = {}
    recv_ts = {}
    lock = asyncio.Lock()

    async def send_one(i):
        ws_s = ws_list[i]
        r_uid = users[N + i]["uid"]
        mid = f"mp{wid}-{i}"
        t0 = time.perf_counter_ns()
        await ws_s.send(json.dumps({
            "type": "message", "toUserId": r_uid,
            "msgId": mid, "content": f"msg{wid}", "msgType": 1
        }))
        async with lock:
            send_ts[i] = t0
        await asyncio.wait_for(ws_s.recv(), timeout=10)

    await asyncio.gather(*[send_one(i) for i in range(N)])

    async def recv_one(i):
        ws_r = ws_list[N + i]
        mid = f"mp{wid}-{i}"
        while True:
            try:
                raw = await asyncio.wait_for(ws_r.recv(), timeout=60)
                data = json.loads(raw)
                if data.get("type") == "message" and data.get("msgId") == mid:
                    async with lock:
                        recv_ts[i] = time.perf_counter_ns()
                    return
                elif data.get("type") == "heartbeat":
                    continue
            except asyncio.TimeoutError:
                return

    await asyncio.gather(*[recv_one(i) for i in range(N)])

    for ws in ws_list:
        await ws.close()

    lats = []
    for i in range(N):
        if i in send_ts and i in recv_ts:
            lats.append((recv_ts[i] - send_ts[i]) / 1_000_000)

    queue.put((wid, lats))


def main():
    total_pairs = int(sys.argv[1])
    processes = int(sys.argv[2])
    pairs_per = total_pairs // processes

    ctx = multiprocessing.get_context("spawn")
    queue = ctx.Queue()
    procs = []

    t0 = time.time()
    for w in range(processes):
        p = ctx.Process(target=worker, args=(w, pairs_per, queue))
        procs.append(p)
        p.start()

    all_lat = []
    for _ in range(processes):
        wid, lats = queue.get()
        all_lat.extend(lats)
        n = len(lats)
        if n > 0:
            print(f"  P{wid:>3}: {n} msgs  "
                  f"avg={sum(lats)/n:.0f}ms  p50={sorted(lats)[n//2]:.0f}ms  "
                  f"min={min(lats):.0f}ms  max={max(lats):.0f}ms")

    for p in procs:
        p.join()

    dur = time.time() - t0
    s = sorted(all_lat)
    n = len(s)

    print(f"\n  {'='*55}")
    print(f"  {total_pairs} pairs, {processes} processes ({pairs_per} pairs/process)")
    print(f"  {n}/{total_pairs} msgs  {dur:.1f}s  QPS={total_pairs/dur:.0f}")
    if n > 0:
        print(f"  E2E:  avg={sum(s)/n:.0f}ms  p50={s[n//2]:.0f}ms  "
              f"p95={s[int(n*0.95)]:.0f}ms  p99={s[int(n*0.99)]:.0f}ms  "
              f"min={s[0]:.0f}ms  max={s[-1]:.0f}ms")
    print()


if __name__ == "__main__":
    main()
