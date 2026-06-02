import asyncio, json, time, websockets, multiprocessing, sys, math, os

WS = "ws://localhost:8081/ws"


def group_worker(wid, data_dir, group_idxs, queue):
    asyncio.run(_group_worker(wid, data_dir, group_idxs, queue))


async def _group_worker(wid, data_dir, group_idxs, queue):
    with open(f"{data_dir}/all_users.json") as f:
        users = json.load(f)
    with open(f"{data_dir}/groups.json") as ff:
        groups_data = json.load(ff)

    idx_map = {u["idx"]: u for u in users}
    all_idxs = set()
    sample_members = {}
    for gi in group_idxs:
        g = groups_data[gi]
        all_idxs.add(g["sender_idx"])
        all_idxs.update(g["member_idxs"])

    # Connect all WS
    sem_ws = asyncio.Semaphore(10)
    idx_ws = {}

    async def conn(idx):
        u = idx_map[idx]
        async with sem_ws:
            ws = await websockets.connect(WS)
            await ws.send(json.dumps({"type": "auth", "token": u["token"], "deviceId": f"g{wid}-{idx}"}))
            await asyncio.wait_for(ws.recv(), timeout=15)
            return ws

    c0 = time.perf_counter()
    for idx in all_idxs:
        ws = await conn(idx)
        idx_ws[idx] = ws
    print(f"  W{wid}: {len(idx_ws)} WS connected ({time.perf_counter()-c0:.0f}s)")

    # Each group sends 1 round
    all_lat = []
    for gi in group_idxs:
        g = groups_data[gi]
        mid = f"gb{gi}"
        t0 = time.perf_counter_ns()
        try:
            await idx_ws[g["sender_idx"]].send(json.dumps(
                {"type": "message", "groupId": g["gid"], "msgId": mid, "content": f"X{gi}", "msgType": 1}))
            await asyncio.wait_for(idx_ws[g["sender_idx"]].recv(), timeout=10)
        except:
            pass

        mws = [(i, idx_ws[mi]) for i, mi in enumerate(g["member_idxs"])]
        lats = [None] * len(mws)

        async def recv_one(i, ws):
            try:
                while True:
                    raw = await asyncio.wait_for(ws.recv(), timeout=60)
                    d = json.loads(raw)
                    if d.get("type") == "message" and d.get("msgId") == mid:
                        lats[i] = (time.perf_counter_ns() - t0) / 1e6
                        return
                    elif d.get("type") == "heartbeat":
                        continue
            except:
                pass

        await asyncio.gather(*[recv_one(i, ws) for i, (_, ws) in enumerate(mws)])
        for l in lats:
            if l is not None:
                all_lat.append(l)
        await asyncio.sleep(0.002)

    for ws in idx_ws.values():
        await ws.close()
    queue.put((wid, all_lat))


def bench(data_dir, procs):
    with open(f"{data_dir}/groups.json") as f:
        groups_data = json.load(f)
    GC = len(groups_data)
    if GC == 0:
        print("  no groups to bench")
        return

    gpp = max(1, math.ceil(GC / procs))
    batches = []
    for pi in range(procs):
        s = pi * gpp
        e = min(s + gpp, GC)
        if s < e:
            batches.append(list(range(s, e)))
    n_procs = len(batches)

    print(f"  {GC} groups, {n_procs} processes")
    ctx = multiprocessing.get_context("spawn")
    q = ctx.Queue()
    procs = []
    t0 = time.time()
    for pi in range(n_procs):
        p = ctx.Process(target=group_worker, args=(pi, data_dir, batches[pi], q))
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
    with open(f"{data_dir}/groups.json") as f:
        groups_data = json.load(f)
    total_expected = sum(len(g["member_idxs"]) for g in groups_data)
    print(f"\n  {n}/{total_expected} deliveries  {dur:.0f}s  {GC/dur:.0f} msgs/s")
    if n:
        print(f"  avg={sum(s)/n:.0f}ms  p50={s[n//2]:.0f}ms  p95={s[int(n*0.95)]:.0f}ms  "
              f"p99={s[int(n*0.99)]:.0f}ms  min={s[0]:.0f}ms  max={s[-1]:.0f}ms")
    else:
        print("  NO DATA")


if __name__ == "__main__":
    data_dir = sys.argv[1] if len(sys.argv) > 1 else "scenario_data"
    procs = int(sys.argv[2]) if len(sys.argv) > 2 else 5
    bench(data_dir, procs)
