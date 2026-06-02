import asyncio, json, time, random, aiohttp, websockets
from concurrent.futures import ThreadPoolExecutor, as_completed

BASE = "http://localhost:8080"
WS = "ws://localhost:8081/ws"


def run_worker(worker_id, total_pairs, thread_count):
    pairs_per_thread = total_pairs // thread_count
    if worker_id == thread_count - 1:
        pairs_per_thread = total_pairs - worker_id * pairs_per_thread
    N = pairs_per_thread

    async def do_work():
        nonlocal N
        tag = random.randint(10000, 99999)
        sem = asyncio.Semaphore(30)

        async with aiohttp.ClientSession() as http:
            async def reg(i):
                u = f"mt_{tag}_{worker_id}_{i}"
                async with sem:
                    for retry in range(3):
                        try:
                            r1 = await http.post(f"{BASE}/api/v1/auth/register",
                                                 json={"username": u, "password": "test123", "nickname": f"T{worker_id}"})
                            b1 = await r1.json()
                            if b1.get("code") != 200 and b1.get("message", "").find("已存在") < 0:
                                await asyncio.sleep(0.5)
                                continue
                            r2 = await http.post(f"{BASE}/api/v1/auth/login",
                                                 json={"username": u, "password": "test123"})
                            b2 = await r2.json()
                            data = b2.get("data")
                            if data and data.get("accessToken"):
                                return {"uid": data["userInfo"]["id"], "token": data["accessToken"]}
                            await asyncio.sleep(0.5)
                        except Exception:
                            await asyncio.sleep(0.5)
                    return None
            users_raw = await asyncio.gather(*[reg(i) for i in range(N * 2)])
            users = [u for u in users_raw if u is not None]
            if len(users) < N * 2:
                print(f"  [w{worker_id}] reg fail: {len(users_raw)-len(users)}, retrying failed...")
                fill_needed = N * 2 - len(users)
                retry_results = await asyncio.gather(*[reg(1000 + i) for i in range(fill_needed)])
                users += [u for u in retry_results if u is not None]
            if len(users) < N * 2:
                N = len(users) // 2
                users = users[:N * 2]

        sem_ws = asyncio.Semaphore(40)

        async def conn(u):
            async with sem_ws:
                ws = await websockets.connect(WS)
                await ws.send(json.dumps({"type": "auth", "token": u["token"],
                                          "deviceId": f"mt-{worker_id}"}))
                await asyncio.wait_for(ws.recv(), timeout=10)
                return ws
        ws_list = await asyncio.gather(*[conn(u) for u in users])
        ws_senders = [ws_list[i] for i in range(N)]
        ws_receivers = [ws_list[N + i] for i in range(N)]

        recv_ts = {}
        lock = asyncio.Lock()

        async def reader(ws, expected_idx):
            expected_mid = f"mt-{worker_id}-{expected_idx}"
            while True:
                raw = await ws.recv()
                data = json.loads(raw)
                if data.get("type") == "message" and data.get("msgId") == expected_mid:
                    async with lock:
                        recv_ts[expected_idx] = time.perf_counter_ns()
                    return
                elif data.get("type") == "heartbeat":
                    continue

        reader_tasks = [asyncio.create_task(reader(ws_receivers[i], i)) for i in range(N)]
        await asyncio.sleep(0.2)

        send_ts = {}
        sem_send = asyncio.Semaphore(N)

        async def sender(i):
            ws = ws_senders[i]
            r_uid = users[N + i]["uid"]
            mid = f"mt-{worker_id}-{i}"
            async with sem_send:
                t0 = time.perf_counter_ns()
                await ws.send(json.dumps({
                    "type": "message", "toUserId": r_uid,
                    "msgId": mid, "content": "M", "msgType": 1
                }))
                async with lock:
                    send_ts[i] = t0
                await asyncio.wait_for(ws.recv(), timeout=10)

        await asyncio.gather(*[sender(i) for i in range(N)])

        await asyncio.wait_for(asyncio.gather(*reader_tasks, return_exceptions=True), timeout=60)

        for ws in ws_list:
            await ws.close()

        latencies = []
        for i in range(N):
            if i in send_ts and i in recv_ts:
                latencies.append((recv_ts[i] - send_ts[i]) / 1_000_000)
        return latencies

    return asyncio.run(do_work())


def main():
    import argparse
    p = argparse.ArgumentParser()
    p.add_argument("-n", "--pairs", type=int, default=400)
    p.add_argument("-t", "--threads", type=int, default=1)
    p.add_argument("--list", type=str, default="")
    args = p.parse_args()

    if args.list:
        configs = [(int(x.split(":")[0]), int(x.split(":")[1])) for x in args.list.split(",")]
    else:
        configs = [(args.pairs, args.threads)]

    for total_pairs, thread_count in configs:
        print()
        print("=" * 70)
        print(f"  {total_pairs} pairs, {thread_count} threads ({total_pairs//thread_count} pairs/thread)")
        print("=" * 70)

        t0 = time.time()
        all_lat = []

        with ThreadPoolExecutor(max_workers=thread_count) as ex:
            futures = {
                ex.submit(run_worker, w, total_pairs, thread_count): w
                for w in range(thread_count)
            }
            for f in as_completed(futures):
                w = futures[f]
                lats = f.result()
                all_lat.extend(lats)
                n = len(lats)
                if n > 0:
                    avg = sum(lats) / n
                    print(f"  Thread-{w}: {n} msgs  avg={avg:.0f}ms  "
                          f"min={min(lats):.0f}ms  max={max(lats):.0f}ms")
                else:
                    print(f"  Thread-{w}: 0 msgs")

        dur = time.time() - t0
        s = sorted(all_lat)
        n = len(s)

        print(f"  {'-'*60}")
        print(f"  SUM:  {n}/{total_pairs} msgs  {dur:.1f}s  QPS={total_pairs/dur:.0f}")
        if n > 0:
            print(f"  E2E:  avg={sum(s)/n:.0f}ms  p50={s[n//2]:.0f}ms  "
                  f"p95={s[int(n*0.95)]:.0f}ms  p99={s[int(n*0.99)]:.0f}ms  "
                  f"min={s[0]:.0f}ms  max={s[-1]:.0f}ms")


if __name__ == "__main__":
    main()
