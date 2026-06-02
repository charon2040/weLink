import asyncio, json, time, random, aiohttp, websockets, multiprocessing, sys, math

BASE = "http://localhost:8080"
WS = "ws://localhost:8081/ws"


def reg_proc(wid, total, n_procs, queue):
    asyncio.run(_reg_proc(wid, total, n_procs, queue))


async def _reg_proc(wid, total, n_procs, queue):
    per = math.ceil(total / n_procs)
    s = wid * per
    e = min(s + per, total)
    count = e - s
    if count <= 0:
        queue.put([])
        return
    tag = random.randint(10000, 99999)
    sem = asyncio.Semaphore(25)
    async with aiohttp.ClientSession() as h:
        async def r(i):
            u = f"u{wid}t{tag}n{i}"
            async with sem:
                for _ in range(15):
                    await h.post(f"{BASE}/api/v1/auth/register", json={"username": u, "password": "test123", "nickname": f"U{wid}"})
                    rp = await h.post(f"{BASE}/api/v1/auth/login", json={"username": u, "password": "test123"})
                    d = (await rp.json()).get("data")
                    if d and d.get("accessToken"):
                        return {"idx": s + i, "uid": d["userInfo"]["id"], "token": d["accessToken"]}
                    await asyncio.sleep(0.3)
                return None
        users = await asyncio.gather(*[r(i) for i in range(count)])
        queue.put([u for u in users if u is not None])


def run_private_worker(wid, pairs, queue):
    asyncio.run(_private_worker(wid, pairs, queue))


async def _private_worker(wid, N, queue):
    tag = random.randint(10000, 99999)
    sem = asyncio.Semaphore(25)
    async with aiohttp.ClientSession() as h:
        async def reg(i):
            u = f"pv{wid}t{tag}u{i}"
            async with sem:
                for _ in range(10):
                    await h.post(f"{BASE}/api/v1/auth/register", json={"username": u, "password": "test123", "nickname": f"P{wid}"})
                    rp = await h.post(f"{BASE}/api/v1/auth/login", json={"username": u, "password": "test123"})
                    d = (await rp.json()).get("data")
                    if d and d.get("accessToken"):
                        return {"uid": d["userInfo"]["id"], "token": d["accessToken"]}
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

    sem_ws = asyncio.Semaphore(12)
    async def conn(u):
        async with sem_ws:
            ws = await websockets.connect(WS)
            await ws.send(json.dumps({"type": "auth", "token": u["token"], "deviceId": f"pv-{wid}"}))
            await asyncio.wait_for(ws.recv(), timeout=10)
            return ws
    ws_list = await asyncio.gather(*[conn(u) for u in users])
    lats = []
    for i in range(N):
        ws_s = ws_list[i]
        ws_r = ws_list[N + i]
        r_uid = users[N + i]["uid"]
        mid = f"pv{wid}-{i}"
        t0 = time.perf_counter_ns()
        await ws_s.send(json.dumps({"type": "message", "toUserId": r_uid, "msgId": mid, "content": f"m{wid}", "msgType": 1}))
        await asyncio.wait_for(ws_s.recv(), timeout=10)
        while True:
            raw = await asyncio.wait_for(ws_r.recv(), timeout=30)
            d = json.loads(raw)
            if d.get("type") == "message" and d.get("msgId") == mid:
                lats.append((time.perf_counter_ns() - t0) / 1_000_000)
                break
            elif d.get("type") == "heartbeat":
                continue
    for ws in ws_list:
        await ws.close()
    queue.put((wid, lats))


def run_group_worker(wid, g_prefix, group_idxs, rounds, queue):
    asyncio.run(_group_worker(wid, g_prefix, group_idxs, rounds, queue))


async def _group_worker(wid, g_prefix, group_idxs, rounds, queue):
    with open(g_prefix + "_users.json") as f:
        users = json.load(f)
    with open(g_prefix + "_groups.json") as ff:
        groups_data = json.load(ff)

    idx_map = {u["idx"]: u for u in users}
    all_idxs = set()
    for gi in group_idxs:
        g = groups_data[gi]
        all_idxs.add(g["sender_idx"])
        all_idxs.update(g["member_idxs"])

    sem_ws = asyncio.Semaphore(10)
    idx_ws = {}
    async def conn(idx):
        u = idx_map[idx]
        async with sem_ws:
            ws = await websockets.connect(WS)
            await ws.send(json.dumps({"type": "auth", "token": u["token"], "deviceId": f"gp{wid}-{idx}"}))
            await asyncio.wait_for(ws.recv(), timeout=15)
            return ws

    for idx in all_idxs:
        ws = await conn(idx)
        idx_ws[idx] = ws

    all_lat = []
    for r in range(rounds):
        for gi in group_idxs:
            g = groups_data[gi]
            mid = f"gp{gi}r{r}"
            t0 = time.perf_counter_ns()
            await idx_ws[g["sender_idx"]].send(json.dumps({"type": "message", "groupId": g["gid"], "msgId": mid, "content": f"G-{gi}", "msgType": 1}))
            try:
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


def print_stats(label, all_lat, total_expected, dur=None, msg_rate=None):
    s = sorted(all_lat)
    n = len(s)
    r = sum(s) / n if n else 0
    print(f"  [{label}] {n}/{total_expected if total_expected else '?'} deliveries", end="")
    if dur:
        print(f"  {dur:.0f}s", end="")
    if msg_rate:
        print(f"  {msg_rate:.0f} msgs/s", end="")
    print()
    if n:
        print(f"         avg={r:.0f}ms  p50={s[n//2]:.0f}ms  p95={s[int(n*0.95)]:.0f}ms  "
              f"p99={s[int(n*0.99)]:.0f}ms  min={s[0]:.0f}ms  max={s[-1]:.0f}ms")
    else:
        print(f"         NO DATA")
    print()


def scenario_group(label, GC, MPG, ROUNDS):
    total_users = GC * (MPG + 1)
    print(f"\n{'='*65}")
    print(f"  {label}: {GC} groups x {MPG} members x {ROUNDS} rounds = {total_users} users")
    print(f"{'='*65}")

    print(f"  Registering {total_users} users...")
    ctx = multiprocessing.get_context("spawn")
    q = ctx.Queue()
    n_procs = 20
    procs = []
    for w in range(n_procs):
        p = ctx.Process(target=reg_proc, args=(w, total_users, n_procs, q))
        procs.append(p)
        p.start()

    all_users = []
    for _ in range(n_procs):
        batch = q.get()
        all_users.extend(batch)
    for p in procs:
        p.join()
    all_users.sort(key=lambda x: x["idx"])
    print(f"  Registered {len(all_users)}/{total_users}")

    if len(all_users) < total_users:
        print("  FAILED - insufficient users")
        return

    # Create groups
    print(f"  Creating {GC} groups...")
    groups = []

    async def _mk():
        async with aiohttp.ClientSession() as h:
            for g in range(GC):
                sender = all_users[g * (MPG + 1)]
                base = g * (MPG + 1) + 1
                b1 = [all_users[base + i]["uid"] for i in range(min(50, MPG))]
                try:
                    r = await h.post(f"{BASE}/api/v1/group",
                                     json={"groupName": f"G{g}", "memberIds": b1},
                                     headers={"Authorization": f"Bearer {sender['token']}"})
                    body = await r.json()
                    gid = body.get("data", {}).get("id") if body else None
                except:
                    gid = None
                if gid:
                    groups.append({"gid": gid, "sender_idx": g * (MPG + 1),
                                   "member_idxs": list(range(base, base + len(b1)))})
                    if MPG > 50:
                        for bs in range(50, MPG, 50):
                            be = min(bs + 50, MPG)
                            batch = [all_users[base + i]["uid"] for i in range(bs, be)]
                            try:
                                await h.post(f"{BASE}/api/v1/group/{gid}/invite", json=batch,
                                             headers={"Authorization": f"Bearer {sender['token']}"})
                                groups[-1]["member_idxs"].extend(range(base + bs, base + be))
                            except:
                                pass
                            await asyncio.sleep(0.02)
                else:
                    print(f"  WARN: Group {g} create failed, retrying with 10 members")
                    b1_small = [all_users[base + i]["uid"] for i in range(min(10, MPG))]
                    try:
                        r = await h.post(f"{BASE}/api/v1/group",
                                         json={"groupName": f"G{g}", "memberIds": b1_small},
                                         headers={"Authorization": f"Bearer {sender['token']}"})
                        body = await r.json()
                        gid = body.get("data", {}).get("id") if body else None
                    except:
                        gid = None
                    if gid:
                        groups.append({"gid": gid, "sender_idx": g * (MPG + 1),
                                       "member_idxs": list(range(base, base + len(b1_small)))})
                        if MPG > 10:
                            for bs in range(10, MPG, 10):
                                be = min(bs + 10, MPG)
                                batch = [all_users[base + i]["uid"] for i in range(bs, be)]
                                try:
                                    await h.post(f"{BASE}/api/v1/group/{gid}/invite", json=batch,
                                                 headers={"Authorization": f"Bearer {sender['token']}"})
                                    groups[-1]["member_idxs"].extend(range(base + bs, base + be))
                                except:
                                    pass
                                await asyncio.sleep(0.05)
                    else:
                        print(f"  WARN: Group {g} still FAILED")
                await asyncio.sleep(0.02)

    asyncio.run(_mk())

    prefix = f"_big_{label.replace(' ','_')}"
    with open(prefix + "_users.json", "w") as f:
        json.dump([{"idx": u["idx"], "uid": u["uid"], "token": u["token"]} for u in all_users], f)
    with open(prefix + "_groups.json", "w") as ff:
        json.dump(groups, ff)
    print(f"  Created {len(groups)}/{GC} groups")

    if len(groups) == 0:
        print("  FAILED - no groups")
        return

    time.sleep(2)

    # Benchmark
    print(f"  Running benchmark with {len(groups)} groups...")
    gpp = max(1, math.ceil(len(groups) / 10))
    batches = []
    for pi in range(10):
        s = pi * gpp
        e = min(s + gpp, len(groups))
        if s < e:
            batches.append(list(range(s, e)))

    q2 = ctx.Queue()
    procs2 = []
    for pi, batch in enumerate(batches):
        p = ctx.Process(target=run_group_worker, args=(pi, prefix, batch, ROUNDS, q2))
        procs2.append(p)
        p.start()

    all_lat = []
    for _ in range(len(batches)):
        wid, lats = q2.get()
        all_lat.extend(lats)
    for p in procs2:
        p.join()

    total_expected = len(groups) * ROUNDS * MPG
    print_stats(label, all_lat, total_expected)


def scenario_private(PAIRS):
    total_users = PAIRS * 2
    n_procs = 40
    print(f"\n{'='*65}")
    print(f"  私聊: {PAIRS} pairs = {total_users} users, {n_procs} processes")
    print(f"{'='*65}")

    pairs_per = PAIRS // n_procs
    ctx = multiprocessing.get_context("spawn")
    q = ctx.Queue()
    procs = []
    t0 = time.time()
    for w in range(n_procs):
        p = ctx.Process(target=run_private_worker, args=(w, pairs_per, q))
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
    print(f"\n  [{PAIRS}对私聊] {n}/{PAIRS} deliveries  {dur:.0f}s  {msg_rate:.0f} msgs/s")
    if n:
        print(f"         avg={sum(s)/n:.0f}ms  p50={s[n//2]:.0f}ms  p95={s[int(n*0.95)]:.0f}ms  "
              f"p99={s[int(n*0.99)]:.0f}ms  min={s[0]:.0f}ms  max={s[-1]:.0f}ms")
    print()


if __name__ == "__main__":
    import_file = sys.argv[1] if len(sys.argv) > 1 else ""
    if import_file == "private":
        scenario_private(int(sys.argv[2]) if len(sys.argv) > 2 else 500)
    elif import_file:
        GC = int(sys.argv[2])
        MPG = int(sys.argv[3])
        ROUNDS = int(sys.argv[4]) if len(sys.argv) > 4 else 1
        scenario_group(import_file, GC, MPG, ROUNDS)
    else:
        # Run all 4 scenarios
        print("\n" + "=" * 70)
        print("  WeLink 全场景 E2E 压测")
        print("=" * 70)

        # Scenario A: 5 groups x 100 members (test group mechanism first)
        scenario_group("5群x100人(预热)", 5, 100, 1)

        # Scenario B: 50 groups x 100 members
        scenario_group("50群x100人", 50, 100, 1)

        # Scenario C: 100 groups x 50 members
        scenario_group("100群x50人", 100, 50, 1)

        # Scenario D: Private chat 5000 pairs (run as separate script to avoid memory issues)
        print("\n  私聊5000对请单独运行: python scenario_all.py private 5000")
