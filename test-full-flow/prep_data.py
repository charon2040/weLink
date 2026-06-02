import asyncio, json, time, random, aiohttp, multiprocessing, sys, math, os

BASE = "http://localhost:8080"


def reg_proc(wid, total, n_procs, out_dir, queue):
    asyncio.run(_reg(wid, total, n_procs, out_dir, queue))


async def _reg(wid, total, n_procs, out_dir, queue):
    per = math.ceil(total / n_procs)
    s = wid * per
    e = min(s + per, total)
    count = e - s
    if count <= 0:
        queue.put((wid, 0))
        return
    tag = random.randint(10000, 99999)
    sem = asyncio.Semaphore(20)
    ok = 0
    async with aiohttp.ClientSession() as h:
        async def r(i):
            nonlocal ok
            u = f"p{wid}t{tag}n{i}"
            async with sem:
                for retry in range(20):
                    try:
                        await h.post(f"{BASE}/api/v1/auth/register",
                                     json={"username": u, "password": "test123", "nickname": f"P{wid}"})
                        rp = await h.post(f"{BASE}/api/v1/auth/login",
                                          json={"username": u, "password": "test123"})
                        d = (await rp.json()).get("data")
                        if d and d.get("accessToken"):
                            ok += 1
                            return {"idx": s + i, "uid": d["userInfo"]["id"], "token": d["accessToken"]}
                    except:
                        pass
                    await asyncio.sleep(0.5 + random.random() * 0.5)
                return None
        users = await asyncio.gather(*[r(i) for i in range(count)])
        users = [u for u in users if u is not None]
        with open(f"{out_dir}/users_{wid}.json", "w") as f:
            json.dump(users, f)
        queue.put((wid, len(users)))


async def create_groups(all_users, GC, MPG, out_dir):
    groups = []
    async with aiohttp.ClientSession() as h:
        for g in range(GC):
            sender = all_users[g * (MPG + 1)]
            base = g * (MPG + 1) + 1
            prefix = "G" * (MPG // 10) if MPG >= 100 else ""
            print(f"  Group {g+1}/{GC}: creating...", end=" ")
            # Try batch sizes from small to large until one works
            for batch_n in [5, 10, 20]:
                b1 = [all_users[base + i]["uid"] for i in range(min(batch_n, MPG))]
                try:
                    r = await h.post(f"{BASE}/api/v1/group",
                                     json={"groupName": f"{prefix}Grp{g}", "memberIds": b1},
                                     headers={"Authorization": f"Bearer {sender['token']}"})
                    body = await r.json()
                    gid = body.get("data", {}).get("id") if body else None
                except:
                    gid = None
                if gid:
                    groups.append({"gid": gid, "sender_idx": g * (MPG + 1),
                                   "member_idxs": list(range(base, base + len(b1)))})
                    break
            if not gid:
                print(f"FAILED (cannot create)")
                continue
            # Invite remaining members
            done = len(b1)
            while done < MPG:
                bs = 10
                be = min(done + bs, MPG)
                batch = [all_users[base + i]["uid"] for i in range(done, be)]
                try:
                    r = await h.post(f"{BASE}/api/v1/group/{gid}/invite", json=batch,
                                     headers={"Authorization": f"Bearer {sender['token']}"})
                    body = await r.json()
                    if body.get("code") == 200 or body.get("code") == 0:
                        groups[-1]["member_idxs"].extend(range(base + done, base + be))
                        done = be
                    else:
                        groups[-1]["member_idxs"].append(base + done)
                        done += 1
                except:
                    done += 1
                await asyncio.sleep(0.05)
            print(f"id={gid}, {len(groups[-1]['member_idxs'])} members")
            await asyncio.sleep(0.05)

    with open(f"{out_dir}/groups.json", "w") as f:
        json.dump(groups, f)
    return groups


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else "prep"
    out_dir = sys.argv[2] if len(sys.argv) > 2 else "scenario_data"
    GC = int(sys.argv[3]) if len(sys.argv) > 3 else 5
    MPG = int(sys.argv[4]) if len(sys.argv) > 4 else 100

    total_users = GC * (MPG + 1)
    os.makedirs(out_dir, exist_ok=True)

    print(f"\n  Preparing: {GC} groups x {MPG} members = {total_users} users")
    print(f"  Output: {out_dir}/\n")

    # Phase 1: Register users
    print(f"  Phase 1: Registering {total_users} users (20 processes)...")
    t0 = time.time()
    ctx = multiprocessing.get_context("spawn")
    q = ctx.Queue()
    n_procs = 20
    procs = []
    for w in range(n_procs):
        p = ctx.Process(target=reg_proc, args=(w, total_users, n_procs, out_dir, q))
        procs.append(p)
        p.start()

    registered = 0
    for _ in range(n_procs):
        wid, n = q.get()
        registered += n
        print(f"    Proc {wid:>2}: {n} users")

    for p in procs:
        p.join()
    print(f"  Registered {registered} users in {time.time()-t0:.0f}s")

    if registered < total_users * 0.9:
        print(f"  FAILED: only {registered}/{total_users} users registered")
        return

    # Merge user files
    all_users = []
    for w in range(n_procs):
        fname = f"{out_dir}/users_{w}.json"
        if os.path.exists(fname):
            with open(fname) as f:
                all_users.extend(json.load(f))
    all_users.sort(key=lambda x: x["idx"])

    with open(f"{out_dir}/all_users.json", "w") as f:
        json.dump([{"idx": u["idx"], "uid": u["uid"], "token": u["token"]} for u in all_users], f)
    print(f"  Merged {len(all_users)} users → all_users.json")

    # Phase 2: Create groups
    print(f"\n  Phase 2: Creating {GC} groups...")
    asyncio.run(create_groups(all_users, GC, MPG, out_dir))

    print(f"\n  Done. Data saved to {out_dir}/")


if __name__ == "__main__":
    main()
