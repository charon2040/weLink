import asyncio, json, time, random, aiohttp, websockets, multiprocessing, sys, math

BASE = "http://localhost:8080"
WS = "ws://localhost:8081/ws"


def private_worker(wid, pairs, queue):
    asyncio.run(_pw(wid, pairs, queue))


async def _pw(wid, N, queue):
    tag = random.randint(10000, 99999)
    sem = asyncio.Semaphore(20)

    async with aiohttp.ClientSession() as h:
        async def reg(i):
            u = f"pb{wid}t{tag}u{i}"
            async with sem:
                for _ in range(15):
                    try:
                        await h.post(f"{BASE}/api/v1/auth/register",
                                     json={"username": u, "password": "test123", "nickname": f"PB{wid}"})
                        rp = await h.post(f"{BASE}/api/v1/auth/login",
                                          json={"username": u, "password": "test123"})
                        d = (await rp.json()).get("data")
                        if d and d.get("accessToken"):
                            return {"uid": d["userInfo"]["id"], "token": d["accessToken"]}
                    except:
                        pass
                    await asyncio.sleep(0.3)
                return None

        users = await asyncio.gather(*[reg(i) for i in range(N * 2)])
        users = [u for u in users if u is not None]
        if len(users) < N * 2:
            N = len(users) // 2
            users = users[:N * 2]
        if N == 0:
            queue.put((wid, []))
            return

    sem_ws = asyncio.Semaphore(10)

    async def conn(u):
        async with sem_ws:
            ws = await websockets.connect(WS)
            await ws.send(json.dumps({"type": "auth", "token": u["token"], "deviceId": f"pb-{wid}"}))
            await asyncio.wait_for(ws.recv(), timeout=10)
            return ws

    c0 = time.perf_counter()
    ws_list = await asyncio.gather(*[conn(u) for u in users])
    print(f"  W{wid}: {len(ws_list)} WS ({time.perf_counter()-c0:.0f}s)")

    lats = []
    for i in range(N):
        ws_s = ws_list[i]
        ws_r = ws_list[N + i]
        r_uid = users[N + i]["uid"]
        mid = f"pb{wid}-{i}"
        t0 = time.perf_counter_ns()
        await ws_s.send(json.dumps({"type": "message", "toUserId": r_uid, "msgId": mid,
                                     "content": f"m{wid}", "msgType": 1}))
        try:
            await asyncio.wait_for(ws_s.recv(), timeout=10)
        except:
            pass
        while True:
            try:
                raw = await asyncio.wait_for(ws_r.recv(), timeout=30)
                d = json.loads(raw)
                if d.get("type") == "message" and d.get("msgId") == mid:
                    lats.append((time.perf_counter_ns() - t0) / 1e6)
                    break
                elif d.get("type") == "heartbeat":
                    continue
            except:
                break

    for ws in ws_list:
        await ws.close()
    queue.put((wid, lats))


def bench(PAIRS, n_procs):
    pairs_per = math.ceil(PAIRS / n_procs)
    print(f"\n  {PAIRS} pairs, {n_procs} processes ({pairs_per} pairs/process)")

    ctx = multiprocessing.get_context("spawn")
    q = ctx.Queue()
    procs = []
    t0 = time.time()
    for w in range(n_procs):
        p = ctx.Process(target=private_worker, args=(w, pairs_per, q))
        procs.append(p)
        p.start()

    all_lat = []
    for _ in range(n_procs):
        wid, lats = q.get()
        all_lat.extend(lats)
    for p in procs:
        p.join()

    dur = time.time() - t0
    s = sorted(all_lat)
    n = len(s)
    msg_rate = PAIRS / dur if dur > 0 else 0
    print(f"\n  {n}/{PAIRS} deliveries  {dur:.0f}s  {msg_rate:.0f} msgs/s")
    if n:
        print(f"  avg={sum(s)/n:.0f}ms  p50={s[n//2]:.0f}ms  p95={s[int(n*0.95)]:.0f}ms  "
              f"p99={s[int(n*0.99)]:.0f}ms  min={s[0]:.0f}ms  max={s[-1]:.0f}ms")
    print()


if __name__ == "__main__":
    PAIRS = int(sys.argv[1]) if len(sys.argv) > 1 else 500
    PROCS = int(sys.argv[2]) if len(sys.argv) > 2 else min(40, PAIRS)
    bench(PAIRS, PROCS)
