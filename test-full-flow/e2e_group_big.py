import asyncio, json, time, random, aiohttp, websockets, multiprocessing, sys, math

BASE = "http://localhost:8080"
WS = "ws://localhost:8081/ws"


def register_batch(worker_id, total_users, n_procs, queue):
    asyncio.run(_register_batch(worker_id, total_users, n_procs, queue))


async def _register_batch(wid, total_users, n_procs, queue):
    per = math.ceil(total_users / n_procs)
    start = wid * per
    end = min(start + per, total_users)
    count = end - start
    if count <= 0:
        queue.put([])
        return

    tag = random.randint(10000, 99999)
    sem = asyncio.Semaphore(25)
    async with aiohttp.ClientSession() as http:
        async def reg(i):
            u = f"bg{wid}t{tag}u{i}"
            async with sem:
                for _ in range(15):
                    await http.post(f"{BASE}/api/v1/auth/register",
                                    json={"username": u, "password": "test123", "nickname": f"B{wid}"})
                    r = await http.post(f"{BASE}/api/v1/auth/login",
                                        json={"username": u, "password": "test123"})
                    d = (await r.json()).get("data")
                    if d and d.get("accessToken"):
                        return {"idx": start + i, "uid": d["userInfo"]["id"], "token": d["accessToken"]}
                    await asyncio.sleep(0.3)
                return None
        users = await asyncio.gather(*[reg(i) for i in range(count)])
        users = [u for u in users if u is not None]
        queue.put(users)


def runner_per_group(wid, prefix, groups_batch, rounds, queue):
    asyncio.run(_runner(wid, prefix, groups_batch, rounds, queue))


async def _runner(wid, prefix, group_batch, rounds, queue):
    with open(prefix + "_users.json") as f:
        users = json.load(f)
    with open(prefix + "_groups.json") as ff:
        groups_data = json.load(ff)

    idx_to_user = {u["idx"]: u for u in users}
    all_idxs = set()
    for gi in group_batch:
        g = groups_data[gi]
        all_idxs.add(g["sender_idx"])
        all_idxs.update(g["member_idxs"])

    sem_ws = asyncio.Semaphore(12)
    idx_to_ws = {}
    async def conn(idx):
        u = idx_to_user[idx]
        async with sem_ws:
            ws = await websockets.connect(WS)
            await ws.send(json.dumps({"type": "auth", "token": u["token"], "deviceId": f"bgp{wid}-{idx}"}))
            await asyncio.wait_for(ws.recv(), timeout=15)
            return ws

    t0 = time.perf_counter()
    for idx in all_idxs:
        ws = await conn(idx)
        idx_to_ws[idx] = ws
    print(f"  P{wid}: {len(idx_to_ws)} WS ({time.perf_counter()-t0:.0f}s)")

    all_lat = []
    for r in range(rounds):
        for gi in group_batch:
            g = groups_data[gi]
            mid = f"bg{gi}r{r}"
            sender_ws = idx_to_ws[g["sender_idx"]]
            t0_ns = time.perf_counter_ns()
            await sender_ws.send(json.dumps({
                "type": "message", "groupId": g["gid"],
                "msgId": mid, "content": f"B-{gi}", "msgType": 1
            }))
            await asyncio.wait_for(sender_ws.recv(), timeout=10)

            mws = [(mi, idx_to_ws[mi]) for mi in g["member_idxs"]]
            lats = [None] * len(mws)
            async def recv_one(i, ws):
                try:
                    while True:
                        raw = await asyncio.wait_for(ws.recv(), timeout=60)
                        d = json.loads(raw)
                        if d.get("type") == "message" and d.get("msgId") == mid:
                            lats[i] = (time.perf_counter_ns() - t0_ns) / 1e6
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

    for ws in idx_to_ws.values():
        await ws.close()
    queue.put((wid, all_lat))


def main():
    prefix = sys.argv[1] if len(sys.argv) > 1 else "_big"
    GC = int(sys.argv[2]) if len(sys.argv) > 2 else 5
    MPG = int(sys.argv[3]) if len(sys.argv) > 3 else 20
    ROUNDS = int(sys.argv[4]) if len(sys.argv) > 4 else 1

    total = GC * (MPG + 1)
    print(f"\n{'='*60}")
    print(f"  {GC} groups x {MPG} members each, {ROUNDS} rounds")
    print(f"{'='*60}\n")

    # Phase 1: Register
    print(f"  Phase 1: Registering {total} users...")
    ctx = multiprocessing.get_context("spawn")
    queue = ctx.Queue()
    n_procs = 20
    procs = []
    for w in range(n_procs):
        p = ctx.Process(target=register_batch, args=(w, total, n_procs, queue))
        procs.append(p)
        p.start()

    all_users = []
    for _ in range(n_procs):
        batch = queue.get()
        all_users.extend(batch)
    for p in procs:
        p.join()
    all_users.sort(key=lambda x: x["idx"])
    print(f"  Registered {len(all_users)}/{total}")

    # Phase 2: Create groups
    print(f"  Phase 2: Creating {GC} groups...")
    groups = []
    async def _create():
        async with aiohttp.ClientSession() as http:
            for g in range(GC):
                sender = all_users[g * (MPG + 1)]
                member_start = g * (MPG + 1) + 1
                batch1 = [all_users[member_start + i]["uid"] for i in range(min(50, MPG))]
                try:
                    r = await http.post(f"{BASE}/api/v1/group",
                                        json={"groupName": f"BigG{g}", "memberIds": batch1},
                                        headers={"Authorization": f"Bearer {sender['token']}"})
                    body = await r.json()
                    gid = body.get("data", {}).get("id") if body else None
                except:
                    gid = None

                if gid:
                    groups.append({"gid": gid, "sender_idx": g * (MPG + 1),
                                   "member_idxs": list(range(member_start, member_start + min(50, MPG)))})
                    if MPG > 50:
                        for bs in range(50, MPG, 50):
                            be = min(bs + 50, MPG)
                            batch = [all_users[member_start + i]["uid"] for i in range(bs, be)]
                            await http.post(f"{BASE}/api/v1/group/{gid}/invite", json=batch,
                                            headers={"Authorization": f"Bearer {sender['token']}"})
                            groups[-1]["member_idxs"].extend(range(member_start + bs, member_start + be))
                            await asyncio.sleep(0.02)
                else:
                    print(f"  Group {g}: FAILED")
                await asyncio.sleep(0.02)
    asyncio.run(_create())

    with open(prefix + "_users.json", "w") as f:
        json.dump([{"idx": u["idx"], "uid": u["uid"], "token": u["token"]} for u in all_users], f)
    with open(prefix + "_groups.json", "w") as ff:
        json.dump(groups, ff)
    print(f"  Created {len(groups)} groups")

    time.sleep(2)

    # Phase 3: Benchmark
    print(f"  Phase 3: Benchmark...")
    gpp = max(1, math.ceil(GC / 10))
    batches = []
    for pi in range(10):
        s = pi * gpp
        e = min(s + gpp, GC)
        if s < e:
            batches.append(list(range(s, e)))
    n_procs = len(batches)

    print(f"  Using {n_procs} worker processes")
    queue2 = ctx.Queue()
    procs2 = []
    for pi in range(n_procs):
        p = ctx.Process(target=runner_per_group, args=(pi, prefix, batches[pi], ROUNDS, queue2))
        procs2.append(p)
        p.start()

    all_lat = []
    for _ in range(n_procs):
        wid, lats = queue2.get()
        all_lat.extend(lats)

    for p in procs2:
        p.join()

    s = sorted(all_lat)
    n = len(s)
    total_deliveries = GC * ROUNDS * MPG
    print(f"\n  {'='*55}")
    print(f"  {GC} groups x {MPG} members x {ROUNDS} rounds")
    print(f"  {n}/{total_deliveries} deliveries")
    if n:
        print(f"  avg={sum(s)/n:.0f}ms  p50={s[n//2]:.0f}ms  p95={s[int(n*0.95)]:.0f}ms  "
              f"p99={s[int(n*0.99)]:.0f}ms  min={s[0]:.0f}ms  max={s[-1]:.0f}ms")
    print()


if __name__ == "__main__":
    main()
