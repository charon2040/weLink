import asyncio, json, time, random, aiohttp, websockets, multiprocessing, sys

BASE = "http://localhost:8080"
WS = "ws://localhost:8081/ws"


async def reg(http, uid_suffix, sem):
    u = f"gtest{uid_suffix}"
    async with sem:
        for _ in range(10):
            await http.post(f"{BASE}/api/v1/auth/register",
                            json={"username": u, "password": "test123", "nickname": f"G{uid_suffix}"})
            r = await http.post(f"{BASE}/api/v1/auth/login",
                                json={"username": u, "password": "test123"})
            d = (await r.json()).get("data")
            if d and d.get("accessToken"):
                return {"uid": d["userInfo"]["id"], "token": d["accessToken"]}
            await asyncio.sleep(0.5)
        return None


async def create_group(http, owner, member_ids, gname):
    r = await http.post(f"{BASE}/api/v1/group",
                        json={"groupName": gname, "memberIds": member_ids},
                        headers={"Authorization": f"Bearer {owner['token']}"})
    data = await r.json()
    return data.get("data", {}).get("id")


async def main():
    GROUP_COUNT = int(sys.argv[1]) if len(sys.argv) > 1 else 5
    MEMBERS_PER = int(sys.argv[2]) if len(sys.argv) > 2 else 10
    ROUNDS = int(sys.argv[3]) if len(sys.argv) > 3 else 3

    total_users = GROUP_COUNT * (MEMBERS_PER + 1)  # +1 for sender per group
    print(f"\n  Creating {total_users} users for {GROUP_COUNT} groups × {MEMBERS_PER}+1 members")

    sem = asyncio.Semaphore(30)
    async with aiohttp.ClientSession() as http:
        users = await asyncio.gather(*[reg(http, i, sem) for i in range(total_users)])
        users = [u for u in users if u is not None]
        print(f"  Registered {len(users)}/{total_users}")

        groups = []
        for g in range(GROUP_COUNT):
            sender = users[g * (MEMBERS_PER + 1)]
            m_ids = [u["uid"] for u in users[g * (MEMBERS_PER + 1) + 1:(g + 1) * (MEMBERS_PER + 1)]]
            gid = await create_group(http, sender, m_ids, f"Grp{g}")
            if gid:
                groups.append({"id": gid, "sender": sender, "members": m_ids})
                print(f"  Group {g}: id={gid}")
            await asyncio.sleep(0.05)

    if not groups:
        print("FAILED to create groups")
        return

    # Connect all members via WS
    print(f"\n  Connecting {total_users} WebSocket clients...")
    sem_ws = asyncio.Semaphore(30)
    all_ws = {}

    async def ws_connect(uid, token, label):
        async with sem_ws:
            ws = await websockets.connect(WS)
            await ws.send(json.dumps({"type": "auth", "token": token, "deviceId": label}))
            await asyncio.wait_for(ws.recv(), timeout=10)
            return ws

    user_ws = {}
    for i, u in enumerate(users):
        ws = await ws_connect(u["uid"], u["token"], f"gtest-{i}")
        user_ws[u["uid"]] = ws

    print(f"  Connected {len(user_ws)} WebSocket clients")

    # Run rounds
    all_lat = []
    for g, grp in enumerate(groups):
        sender_ws = user_ws[grp["sender"]["uid"]]
        receiver_ids = grp["members"]

        for r in range(ROUNDS):
            mid = f"grp{g}-r{r}"
            t0 = time.perf_counter_ns()

            await sender_ws.send(json.dumps({
                "type": "message", "groupId": grp["id"],
                "msgId": mid, "content": f"Hello-{g}-{r}", "msgType": 1
            }))
            await asyncio.wait_for(sender_ws.recv(), timeout=10)  # ACK

            for rid in receiver_ids:
                try:
                    raw = await asyncio.wait_for(user_ws[rid].recv(), timeout=15)
                    data = json.loads(raw)
                    if data.get("type") == "message" and data.get("msgId") == mid:
                        all_lat.append((time.perf_counter_ns() - t0) / 1_000_000)
                    elif data.get("type") == "heartbeat":
                        pass
                except asyncio.TimeoutError:
                    pass
            await asyncio.sleep(0.005)

    for ws in user_ws.values():
        await ws.close()

    s = sorted(all_lat)
    n = len(s)
    expected = GROUP_COUNT * ROUNDS * MEMBERS_PER
    print(f"\n  {'='*55}")
    print(f"  {GROUP_COUNT} groups × {MEMBERS_PER} members × {ROUNDS} rounds")
    print(f"  {n}/{expected} deliveries")
    if n:
        print(f"  avg={sum(s)/n:.0f}ms  p50={s[n//2]:.0f}ms  p95={s[int(n*0.95)]:.0f}ms  "
              f"p99={s[int(n*0.99)]:.0f}ms  min={s[0]:.0f}ms  max={s[-1]:.0f}ms")
    print()


asyncio.run(main())
