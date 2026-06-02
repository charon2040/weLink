import asyncio, json, time, aiohttp, websockets, sys

BASE = "http://localhost:8080"
WS = "ws://localhost:8081/ws"


async def main():
    GROUP_COUNT = int(sys.argv[1])
    MEMBERS_PER = int(sys.argv[2])
    ROUNDS = int(sys.argv[3])

    total_users = GROUP_COUNT * (MEMBERS_PER + 1)
    sem = asyncio.Semaphore(25)
    async with aiohttp.ClientSession() as http:
        async def reg(i):
            u = f"gz{int(time.time()*1000)%10000000}u{i}"
            async with sem:
                for _ in range(10):
                    await http.post(f"{BASE}/api/v1/auth/register", json={"username":u,"password":"test123","nickname":f"G{i}"})
                    r = await http.post(f"{BASE}/api/v1/auth/login", json={"username":u,"password":"test123"})
                    d = (await r.json()).get("data")
                    if d and d.get("accessToken"):
                        return {"uid": d["userInfo"]["id"], "token": d["accessToken"]}
                    await asyncio.sleep(0.3)
                return None

        print(f"  Registering {total_users} users...")
        users = await asyncio.gather(*[reg(i) for i in range(total_users)])
        users = [u for u in users if u is not None]
        print(f"  Registered {len(users)}/{total_users}")

        # Create groups
        groups = []
        for g in range(GROUP_COUNT):
            sender = users[g * (MEMBERS_PER + 1)]
            m_ids = [u["uid"] for u in users[g * (MEMBERS_PER + 1) + 1:(g + 1) * (MEMBERS_PER + 1)]]
            r = await http.post(f"{BASE}/api/v1/group",
                                json={"groupName": f"G{g}", "memberIds": m_ids},
                                headers={"Authorization": f"Bearer {sender['token']}"})
            gid = (await r.json()).get("data", {}).get("id")
            if gid:
                groups.append({"id": gid, "sender": sender, "members": m_ids})
        print(f"  Created {len(groups)} groups")

    if not groups:
        print("FAILED")
        return

    # Connect ALL via WS
    sem_ws = asyncio.Semaphore(20)
    async def conn(uid, token, label):
        async with sem_ws:
            ws = await websockets.connect(WS)
            await ws.send(json.dumps({"type": "auth", "token": token, "deviceId": label}))
            await asyncio.wait_for(ws.recv(), timeout=10)
            return ws

    print(f"  Connecting {total_users} WS clients...")
    user_ws = {}
    for i, u in enumerate(users):
        ws = await conn(u["uid"], u["token"], f"gztest-{i}")
        user_ws[u["uid"]] = ws
    print(f"  Connected {len(user_ws)}")

    total_senders = len(groups) * ROUNDS
    total_deliveries = len(groups) * ROUNDS * MEMBERS_PER
    all_lat = []

    t_start = time.perf_counter()

    async def send_round(g, r):
        grp = groups[g]
        mid = f"grp{g}-r{r}"
        t0 = time.perf_counter_ns()
        await user_ws[grp["sender"]["uid"]].send(json.dumps({
            "type": "message", "groupId": grp["id"],
            "msgId": mid, "content": f"Hello-{g}", "msgType": 1
        }))
        await asyncio.wait_for(user_ws[grp["sender"]["uid"]].recv(), timeout=10)

        lats = []
        for uid in grp["members"]:
            try:
                raw = await asyncio.wait_for(user_ws[uid].recv(), timeout=30)
                data = json.loads(raw)
                if data.get("type") == "message" and data.get("msgId") == mid:
                    lats.append((time.perf_counter_ns() - t0) / 1_000_000)
                elif data.get("type") == "heartbeat":
                    pass
            except asyncio.TimeoutError:
                pass
        return lats

    # Run rounds sequentially (1 sender per group per round)
    for r in range(ROUNDS):
        for g in range(GROUP_COUNT):
            lats = await send_round(g, r)
            all_lat.extend(lats)

    dur = time.perf_counter() - t_start

    for ws in user_ws.values():
        await ws.close()

    s = sorted(all_lat)
    n = len(s)
    msg_rate = total_senders / dur
    print(f"\n  {'='*60}")
    print(f"  {GROUP_COUNT} groups x {MEMBERS_PER} members x {ROUNDS} rounds")
    print(f"  {n}/{total_deliveries} deliveries  {dur:.1f}s  {msg_rate:.0f} msgs/s")
    if n:
        print(f"  avg={sum(s)/n:.0f}ms  p50={s[n//2]:.0f}ms  p95={s[int(n*0.95)]:.0f}ms  "
              f"p99={s[int(n*0.99)]:.0f}ms  min={s[0]:.0f}ms  max={s[-1]:.0f}ms")
    print()


asyncio.run(main())
