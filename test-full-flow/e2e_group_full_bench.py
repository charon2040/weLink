import asyncio, json, time, aiohttp, websockets, sys

BASE = "http://localhost:8080"
WS = "ws://localhost:8081/ws"


async def test(name, GC, MPG, ROUNDS):
    total = GC * (MPG + 1)
    sem = asyncio.Semaphore(30)
    async with aiohttp.ClientSession() as h:
        async def reg(i):
            u = f"t{int(time.time()*1000) % 99999}r{i}"
            async with sem:
                for _ in range(10):
                    await h.post(f"{BASE}/api/v1/auth/register",
                                 json={"username": u, "password": "test123", "nickname": f"T{i}"})
                    r = await h.post(f"{BASE}/api/v1/auth/login",
                                     json={"username": u, "password": "test123"})
                    d = (await r.json()).get("data")
                    if d and d.get("accessToken"):
                        return {"uid": d["userInfo"]["id"], "token": d["accessToken"]}
                    await asyncio.sleep(0.3)
                return None

        t0 = time.perf_counter()
        users_raw = await asyncio.gather(*[reg(i) for i in range(total)])
        users = [u for u in users_raw if u is not None]
        reg_time = time.perf_counter() - t0
        print(f"  [{name}] Registered {len(users)}/{total} in {reg_time:.0f}s")

        if len(users) < total:
            print(f"  [{name}] FAIL: only {len(users)} users")
            return

        groups = []
        for g in range(GC):
            sender = users[g * (MPG + 1)]
            m_ids = [u["uid"] for u in users[g * (MPG + 1) + 1:(g + 1) * (MPG + 1)]]
            body = {"groupName": f"{name}G{g}", "memberIds": m_ids}
            r = await h.post(f"{BASE}/api/v1/group",
                             json=body,
                             headers={"Authorization": f"Bearer {sender['token']}"})
            gid = (await r.json()).get("data", {}).get("id")
            if gid:
                groups.append({"id": gid, "sender": sender, "members": m_ids})
        print(f"  [{name}] Created {len(groups)} groups")
        if len(groups) < GC:
            print(f"  [{name}] FAIL: only {len(groups)} groups")
            return

    t0 = time.perf_counter()
    sem_ws = asyncio.Semaphore(20)
    async def conn(uid, tok, label):
        async with sem_ws:
            ws = await websockets.connect(WS)
            await ws.send(json.dumps({"type": "auth", "token": tok, "deviceId": label}))
            await asyncio.wait_for(ws.recv(), timeout=10)
            return ws

    user_ws = {}
    for i, u in enumerate(users):
        ws = await conn(u["uid"], u["token"], f"{name}-{i}")
        user_ws[u["uid"]] = ws
    print(f"  [{name}] Connected {len(user_ws)} WS in {time.perf_counter()-t0:.0f}s")

    all_lat = []
    t_start = time.perf_counter()
    for r in range(ROUNDS):
        for g in range(GC):
            grp = groups[g]
            mid = f"{name}g{g}r{r}"
            t0 = time.perf_counter_ns()
            await user_ws[grp["sender"]["uid"]].send(json.dumps({
                "type": "message", "groupId": grp["id"],
                "msgId": mid, "content": f"H-{g}", "msgType": 1
            }))
            await asyncio.wait_for(user_ws[grp["sender"]["uid"]].recv(), timeout=10)

            mws = [user_ws[uid] for uid in grp["members"]]
            lats = [None] * len(mws)

            async def recv_one(idx, ws):
                try:
                    while True:
                        raw = await asyncio.wait_for(ws.recv(), timeout=30)
                        d = json.loads(raw)
                        if d.get("type") == "message" and d.get("msgId") == mid:
                            lats[idx] = (time.perf_counter_ns() - t0) / 1e6
                            return
                        elif d.get("type") == "heartbeat":
                            continue
                except:
                    pass

            await asyncio.gather(*[recv_one(i, w) for i, w in enumerate(mws)])
            for l in lats:
                if l is not None:
                    all_lat.append(l)
            await asyncio.sleep(0.005)

    dur = time.perf_counter() - t_start
    for ws in user_ws.values():
        await ws.close()

    s = sorted(all_lat)
    n = len(s)
    total_deliveries = GC * ROUNDS * MPG
    r = sum(s) / n if n else 0
    print(f"  [{name}] {n}/{total_deliveries} deliv  {dur:.0f}s  {GC*ROUNDS/dur:.0f}msgs/s")
    print(f"         avg={r:.0f}ms  p50={s[n//2] if n else 0:.0f}ms  p95={s[int(n*.95)] if n else 0:.0f}ms  "
          f"p99={s[int(n*.99)] if n else 0:.0f}ms  min={s[0] if n else 0:.0f}ms  max={s[-1] if n else 0:.0f}ms")
    print()


async def main():
    print()
    print("=" * 70)
    for name, gc, mpg, rd in [
        ("5群x80人", 5, 80, 2),
        ("10群x50人", 10, 50, 2),
        ("20群x50人", 20, 50, 1),
        ("50群x10人", 50, 10, 1),
    ]:
        await test(name, gc, mpg, rd)
    print("=" * 70)
    print()


asyncio.run(main())
