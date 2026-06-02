import asyncio, json, time, random, aiohttp, websockets, multiprocessing, sys, os

BASE = "http://localhost:8080"
WS = "ws://localhost:8081/ws"


def setup_process(worker_id, group_count, members_per_group, groups_queue, member_db_queue):
    """Each process creates its share of users and optionally one group."""
    asyncio.run(_setup(worker_id, group_count, members_per_group, groups_queue, member_db_queue))


async def _setup(worker_id, group_count, members_per_group, groups_queue, member_db_queue):
    tag = random.randint(10000, 99999)
    sem = asyncio.Semaphore(25)

    async with aiohttp.ClientSession() as http:
        async def reg_user(i):
            u = f"g{worker_id}t{tag}u{i}"
            async with sem:
                for _ in range(15):
                    await http.post(f"{BASE}/api/v1/auth/register",
                                    json={"username": u, "password": "test123", "nickname": f"G{worker_id}U{i}"})
                    r = await http.post(f"{BASE}/api/v1/auth/login",
                                        json={"username": u, "password": "test123"})
                    d = (await r.json()).get("data")
                    if d and d.get("accessToken"):
                        return {"username": u, "uid": d["userInfo"]["id"], "token": d["accessToken"]}
                    await asyncio.sleep(0.5)
                return None

        # Each process creates members for its share
        total_needed = group_count * members_per_group
        per_process = total_needed // 20  # 20 setup processes
        start_idx = worker_id * per_process
        end_idx = start_idx + per_process if worker_id < 19 else total_needed
        count = end_idx - start_idx

        users = await asyncio.gather(*[reg_user(i) for i in range(count)])
        users = [u for u in users if u is not None]

        # Register all users to a shared dict (via queue)
        member_db_queue.put({"worker": worker_id, "users": users, "start_idx": start_idx})
        print(f"  Setup-P{worker_id:>3}: registered {len(users)}/{count} users (idx {start_idx}-{start_idx+len(users)-1})")


def main_setup(group_count, members_per_group):
    """Phase 0: Create all users and groups, collect credentials."""
    total_members = group_count * members_per_group
    print(f"\n{'='*65}")
    print(f"  Phase 0: Registering ~{total_members} users ({group_count} groups × {members_per_group} members)")
    print(f"{'='*65}")

    ctx = multiprocessing.get_context("spawn")
    queue = ctx.Queue()

    n_procs = 20
    procs = []
    for w in range(n_procs):
        p = ctx.Process(target=setup_process, args=(w, group_count, members_per_group, None, queue))
        procs.append(p)
        p.start()

    all_users = []
    for _ in range(n_procs):
        data = queue.get()
        for u in data["users"]:
            all_users.append(u)

    for p in procs:
        p.join()

    print(f"  Total registered: {len(all_users)} users")

    # Phase 1: Create groups and invite members
    print(f"\n{'='*65}")
    print(f"  Phase 1: Creating {group_count} groups and inviting members")
    print(f"{'='*65}")

    groups = []

    async def _create_groups():
        async with aiohttp.ClientSession() as http:
            # First user in each group is the owner/creator
            for g in range(group_count):
                # Owner is a random user not yet assigned to a group
                owner = all_users[g % len(all_users)]

                # Pick members (distinct from owner, not overlapping with other groups if possible)
                member_range = members_per_group
                member_users = all_users[group_count + g * member_range:group_count + (g+1) * member_range]
                member_ids = [u["uid"] for u in member_users[:member_range]]

                # Create group
                r = await http.post(f"{BASE}/api/v1/group",
                                    json={"groupName": f"StressGroup{g}", "memberIds": member_ids},
                                    headers={"Authorization": f"Bearer {owner['token']}"})
                data = (await r.json())
                gid = data.get("data", {}).get("id") if data.get("data") else None

                if gid:
                    groups.append({"id": gid, "owner": owner, "members": member_users[:member_range]})
                    print(f"  Group {g}: id={gid}, members={len(member_users[:member_range])}")
                else:
                    # Retry with smaller member list
                    member_ids_small = [u["uid"] for u in member_users[:10]]
                    r = await http.post(f"{BASE}/api/v1/group",
                                        json={"groupName": f"StressGroup{g}", "memberIds": member_ids_small},
                                        headers={"Authorization": f"Bearer {owner['token']}"})
                    data = (await r.json())
                    gid = data.get("data", {}).get("id") if data.get("data") else None
                    if gid:
                        # Add remaining members via invite
                        groups.append({"id": gid, "owner": owner, "members": member_users[:10]})
                        for batch_start in range(10, member_range, 10):
                            batch = member_users[batch_start:batch_start+10]
                            await http.post(f"{BASE}/api/v1/group/{gid}/invite",
                                            json=[u["uid"] for u in batch],
                                            headers={"Authorization": f"Bearer {owner['token']}"})
                            groups[-1]["members"].extend(batch)
                            await asyncio.sleep(0.1)
                        print(f"  Group {g}: id={gid}, members={len(groups[-1]['members'])} (batched)")
                    else:
                        print(f"  Group {g}: FAILED to create: {data}")

                await asyncio.sleep(0.1)

    asyncio.run(_create_groups())

    with open("group_test_data.json", "w") as f:
        json.dump({"groups": groups}, f)

    print(f"  Created {len(groups)} groups")
    return groups


GROUPS_DATA = None


def runner_process(worker_id, group_idx, round_count, queue):
    """One process connects N members of one group and measures delivery."""
    asyncio.run(_runner(worker_id, group_idx, round_count, queue))


async def _runner(worker_id, group_idx, round_count, queue):
    global GROUPS_DATA
    if GROUPS_DATA is None:
        with open("group_test_data.json") as f:
            GROUPS_DATA = json.load(f)

    group = GROUPS_DATA["groups"][group_idx]
    members = group["members"]
    gid = group["id"]
    owner = group["owner"]

    # Owner + all members connect via WS
    all_users = [owner] + members
    N = len(all_users)

    sem_ws = asyncio.Semaphore(15)
    async def conn(i):
        u = all_users[i]
        async with sem_ws:
            ws = await websockets.connect(WS)
            await ws.send(json.dumps({"type": "auth", "token": u["token"], "deviceId": f"grp-{worker_id}-{i}"}))
            await asyncio.wait_for(ws.recv(), timeout=10)
            return ws

    ws_list = []
    for i in range(N):
        ws = await conn(i)
        ws_list.append(ws)

    all_lat = []

    for r in range(round_count):
        mid = f"grp{gid}-{worker_id}-{r}"
        t0 = time.perf_counter_ns()

        # Owner sends message
        await ws_list[0].send(json.dumps({
            "type": "message", "groupId": gid,
            "msgId": mid, "content": f"group-msg-{worker_id}-{r}", "msgType": 1
        }))
        # Wait for ACK
        ack = await asyncio.wait_for(ws_list[0].recv(), timeout=10)

        # All N-1 members should receive
        received = set()
        deadline = time.perf_counter_ns() + 20_000_000_000  # 20s timeout
        while len(received) < N - 1:
            remaining_ns = deadline - time.perf_counter_ns()
            if remaining_ns <= 0:
                break
            try:
                raw = await asyncio.wait_for(
                    ws_list[len(received) + 1].recv(),
                    timeout=remaining_ns / 1_000_000_000
                )
                data = json.loads(raw)
                if data.get("type") == "message" and data.get("msgId") == mid:
                    t1 = time.perf_counter_ns()
                    all_lat.append((t1 - t0) / 1_000_000)
                elif data.get("type") == "heartbeat":
                    continue
            except asyncio.TimeoutError:
                break

        await asyncio.sleep(0.01)

    for ws in ws_list:
        await ws.close()

    queue.put((group_idx, all_lat))


def run_group_bench(group_count, members_per_group, rounds):
    print(f"\n{'='*65}")
    print(f"  Running: {group_count} groups, {members_per_group} members, {rounds} rounds each")
    print(f"{'='*65}")

    ctx = multiprocessing.get_context("spawn")
    queue = ctx.Queue()

    all_lat = []
    procs = []
    for g in range(group_count):
        p = ctx.Process(target=runner_process, args=(g, g, rounds, queue))
        procs.append(p)
        p.start()

    for _ in range(group_count):
        gid, lats = queue.get()
        all_lat.extend(lats)
        n = len(lats)
        if n > 0:
            s = sorted(lats)
            print(f"  G{gid}: {n} recv  p50={s[n//2]:.0f}ms  min={s[0]:.0f}ms  max={s[-1]:.0f}ms")

    for p in procs:
        p.join()

    s = sorted(all_lat)
    n = len(s)
    total_msgs = group_count * rounds * (members_per_group - 1)
    print(f"\n  {'-'*45}")
    print(f"  Total: {n}/{total_msgs}  (receivers)")
    if n > 0:
        print(f"  avg={sum(s)/n:.0f}ms  p50={s[n//2]:.0f}ms  p95={s[int(n*0.95)]:.0f}ms  "
              f"p99={s[int(n*0.99)]:.0f}ms  min={s[0]:.0f}ms  max={s[-1]:.0f}ms")
    print()


if __name__ == "__main__":
    if len(sys.argv) < 4:
        print("Usage: python e2e_group_stress.py <group_count> <members_per_group> <rounds>")
        print("Examples:")
        print("  python e2e_group_stress.py 5 10 3     # 5 groups, 10 members, 3 rounds → 135 deliveries")
        print("  python e2e_group_stress.py 5 80 3     # 5 groups, 80 members, 3 rounds → 1185 deliveries")
        print("  python e2e_group_stress.py 50 10 1    # 50 groups, 10 members, 1 round → 450 deliveries")
        sys.exit(1)

    GC = int(sys.argv[1])
    MPG = int(sys.argv[2])
    ROUNDS = int(sys.argv[3])

    # Phase 0+1: setup
    groups_data = main_setup(GC, MPG)

    time.sleep(2)

    # Phase 2: benchmark
    run_group_bench(GC, MPG, ROUNDS)
