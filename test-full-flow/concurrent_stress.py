import asyncio
import json
import time
import random
import argparse
from dataclasses import dataclass, field
from datetime import datetime
from typing import Optional

import aiohttp
import websockets
from websockets.asyncio.client import ClientConnection


BASE_URL = "http://localhost:8080"
WS_URL = "ws://localhost:8081/ws"
API = "/api/v1"


@dataclass
class User:
    username: str
    password: str
    nickname: str
    user_id: int = 0
    access_token: str = ""
    refresh_token: str = ""


@dataclass
class BenchResult:
    label: str
    total: int = 0
    success: int = 0
    fail: int = 0
    latencies: list = field(default_factory=list)
    start_time: float = 0.0
    end_time: float = 0.0

    def stats(self):
        if not self.latencies:
            return f"total={self.total} ok={self.success} fail={self.fail}"
        s = sorted(self.latencies)
        n = len(s)
        return (f"total={self.total} ok={self.success} fail={self.fail}  "
                f"avg={sum(s)/n:.1f}ms min={s[0]:.1f}ms max={s[-1]:.1f}ms "
                f"p50={s[n//2]:.1f}ms p95={s[int(n*0.95)]:.1f}ms p99={s[int(n*0.99)]:.1f}ms")


class StressRunner:
    def __init__(self):
        self.users: list[User] = []
        self.reg = BenchResult("register")
        self.login = BenchResult("login")
        self.ws = BenchResult("ws_connect")

    async def _post(self, session, path, body):
        async with session.post(f"{BASE_URL}{API}{path}", json=body) as r:
            return await r.json()

    async def _post_auth(self, session, user, path, body=None):
        headers = {"Authorization": f"Bearer {user.access_token}"}
        async with session.post(f"{BASE_URL}{API}{path}", json=body or {}, headers=headers) as r:
            return await r.json()

    async def _register_one(self, session, u, sem):
        async with sem:
            t0 = time.time()
            try:
                resp = await self._post(session, "/auth/register",
                                        {"username": u.username, "password": u.password, "nickname": u.nickname})
                self.reg.latencies.append((time.time() - t0) * 1000)
                if resp.get("code") == 200:
                    self.reg.success += 1
                    return True
                self.reg.fail += 1
                return False
            except Exception:
                self.reg.latencies.append((time.time() - t0) * 1000)
                self.reg.fail += 1
                return False

    async def _login_one(self, session, u, sem):
        async with sem:
            t0 = time.time()
            try:
                resp = await self._post(session, "/auth/login",
                                        {"username": u.username, "password": u.password})
                self.login.latencies.append((time.time() - t0) * 1000)
                if resp.get("code") == 200:
                    d = resp["data"]
                    u.access_token = d["accessToken"]
                    u.refresh_token = d["refreshToken"]
                    u.user_id = d["userInfo"]["id"]
                    self.login.success += 1
                    return True
                self.login.fail += 1
                return False
            except Exception:
                self.login.latencies.append((time.time() - t0) * 1000)
                self.login.fail += 1
                return False

    async def register_and_login(self, count, prefix, concurrency):
        self.users = [User(f"{prefix}_{i}_{random.randint(10000,99999)}", "test123", f"U{i}")
                      for i in range(count)]
        sem = asyncio.Semaphore(concurrency)
        conn = aiohttp.TCPConnector(limit=concurrency, limit_per_host=concurrency)

        async with aiohttp.ClientSession(connector=conn) as s:
            self.reg.start_time = time.time()
            self.reg.total = len(self.users)
            results = await asyncio.gather(*[self._register_one(s, u, sem) for u in self.users])
            self.reg.end_time = time.time()
            registered = [u for u, ok in zip(self.users, results) if ok is True]
            self.users = registered

            if not self.users:
                print("No users registered, abort.")
                return []

            self.login.start_time = time.time()
            self.login.total = len(self.users)
            results = await asyncio.gather(*[self._login_one(s, u, sem) for u in self.users])
            self.login.end_time = time.time()
            self.users = [u for u, ok in zip(self.users, results) if ok is True]
            return self.users

    async def _ws_connect_one(self, u, sem):
        async with sem:
            self.ws.total += 1
            try:
                ws = await websockets.connect(WS_URL, ping_interval=None, close_timeout=5)
                await ws.send(json.dumps({"type": "auth", "token": u.access_token, "deviceId": "stress"}))
                resp = await asyncio.wait_for(ws.recv(), timeout=10)
                if json.loads(resp).get("status") == "success":
                    self.ws.success += 1
                    return ws
                self.ws.fail += 1
                await ws.close()
                return None
            except Exception:
                self.ws.fail += 1
                return None

    async def batch_ws_connect(self, users, concurrency):
        sem = asyncio.Semaphore(concurrency)
        self.ws.start_time = time.time()
        results = await asyncio.gather(*[self._ws_connect_one(u, sem) for u in users])
        self.ws.end_time = time.time()
        pairs = [(u, ws) for u, ws in zip(users, results) if ws is not None]
        return [u for u, _ in pairs], {u.user_id: ws for u, ws in pairs}

    async def _create_group(self, session, owner, name, sem):
        async with sem:
            try:
                resp = await self._post_auth(session, owner, "/group",
                                             {"groupName": name, "memberIds": []})
                return resp.get("data", {}).get("id")
            except Exception:
                return None

    async def _join_group(self, session, user, gid, sem):
        async with sem:
            try:
                resp = await self._post_auth(session, user, f"/group/join/{gid}")
                return resp.get("code") == 200
            except Exception:
                return False

    async def run_private_chat(self, sender_pool, receiver_pool, ws_map,
                               pairs, msg_concurrency, rounds, msg_per_pair):
        private_send = BenchResult("private_send")
        private_recv = BenchResult("private_recv")
        private_e2e = BenchResult("private_e2e")
        sem = asyncio.Semaphore(msg_concurrency)

        for r in range(rounds):
            private_send.start_time = private_send.start_time or time.time()
            private_e2e.start_time = private_e2e.start_time or time.time()

            tasks = []
            for pi in range(min(len(sender_pool), len(receiver_pool), pairs)):
                a = sender_pool[pi]
                b = receiver_pool[pi]
                wa = ws_map.get(a.user_id)
                wb = ws_map.get(b.user_id)
                if wa and wb:
                    for _ in range(msg_per_pair):
                        private_send.total += 1
                        private_recv.total += 1
                        content = f"[Private R{r+1}] {a.username}->{b.username}"
                        tasks.append(self._private_send_one(
                            wa, a, wb, b, content, sem,
                            private_send, private_recv, private_e2e))
            if tasks:
                await asyncio.gather(*tasks)
            if r < rounds - 1:
                await asyncio.sleep(0.5)

        private_send.end_time = time.time()
        private_e2e.end_time = time.time()
        return private_send, private_recv, private_e2e

    async def _private_send_one(self, sender_ws, sender, receiver_ws, receiver,
                                 content, sem, private_send, private_recv, private_e2e):
        async with sem:
            msg_id = f"priv-{random.randint(100000, 999999)}-{int(time.time()*1000)}"
            t0 = time.time()
            try:
                await sender_ws.send(json.dumps({
                    "type": "message", "toUserId": receiver.user_id,
                    "msgId": msg_id, "content": content, "msgType": 1
                }))
            except Exception:
                private_send.fail += 1
                return

            try:
                ack = await asyncio.wait_for(sender_ws.recv(), timeout=10)
                t1 = time.time()
                data = json.loads(ack)
                if data.get("type") == "message" and data.get("status") == "success":
                    private_send.success += 1
                    private_send.latencies.append((t1 - t0) * 1000)
                else:
                    private_send.fail += 1
                    return
            except Exception:
                private_send.fail += 1
                return

            try:
                while True:
                    msg = await asyncio.wait_for(receiver_ws.recv(), timeout=15)
                    t2 = time.time()
                    data = json.loads(msg)
                    if data.get("type") == "message" and "msgId" in data:
                        private_recv.success += 1
                        private_e2e.latencies.append((t2 - t0) * 1000)
                        return
                    elif data.get("type") == "heartbeat":
                        continue
            except Exception:
                private_recv.fail += 1

    async def run_group_chat(self, all_users, ws_map,
                             group_size, num_groups, msg_concurrency,
                             rounds, msg_per_member):
        sem_http = asyncio.Semaphore(20)
        group_create_lat = BenchResult("group_create")
        group_join_lat = BenchResult("group_join")
        group_send = BenchResult("group_send")
        group_recv = BenchResult("group_recv")
        group_e2e = BenchResult("group_e2e")

        needed = num_groups * (group_size + 1)
        if len(all_users) < needed:
            num_groups = max(1, len(all_users) // (group_size + 1))

        connector = aiohttp.TCPConnector(limit=50, limit_per_host=50)
        groups: list[dict] = []
        user_idx = 0

        async with aiohttp.ClientSession(connector=connector) as s:
            for g in range(num_groups):
                owner = all_users[user_idx]; user_idx += 1
                members = all_users[user_idx:user_idx + group_size]; user_idx += group_size
                name = f"StressGroup_{g}_{random.randint(1000,9999)}"

                t0 = time.time()
                gid = await self._create_group(s, owner, name, sem_http)
                t1 = time.time()
                if not gid:
                    continue
                group_create_lat.latencies.append((t1 - t0) * 1000)
                group_create_lat.success += 1

                for m in members:
                    t0 = time.time()
                    ok = await self._join_group(s, m, gid, sem_http)
                    t1 = time.time()
                    group_join_lat.latencies.append((t1 - t0) * 1000)
                    if ok:
                        group_join_lat.success += 1
                    else:
                        group_join_lat.fail += 1

                all_online = [owner] + [m for m in members if m.user_id in ws_map]
                groups.append({"id": gid, "name": name, "members": all_online})

        group_create_lat.total = num_groups
        group_join_lat.total = group_size * len(groups)
        print(f"  Groups created: {len(groups)}/{num_groups}, joined members: {group_join_lat.success}")

        if not groups:
            return group_create_lat, group_join_lat, group_send, group_recv, group_e2e

        for r in range(rounds):
            group_send.start_time = group_send.start_time or time.time()
            group_e2e.start_time = group_e2e.start_time or time.time()

            sender_tasks = []

            for gi, g in enumerate(groups):
                group_gid = g["id"]
                for sender in g["members"]:
                    ws = ws_map.get(sender.user_id)
                    if not ws:
                        for _ in range(msg_per_member):
                            group_send.total += 1
                            group_send.fail += 1
                        continue

                    async def send_all_for_user(ws=ws, gid=group_gid, sender=sender):
                        for _ in range(msg_per_member):
                            group_send.total += 1
                            mid = f"grp-{random.randint(100000,999999)}-{int(time.time()*1000)}"
                            content = f"[Group R{r+1}] {sender.username}"

                            t0 = time.time()
                            try:
                                await ws.send(json.dumps({
                                    "type": "message", "groupId": gid,
                                    "msgId": mid, "content": content, "msgType": 1
                                }))
                            except Exception:
                                group_send.fail += 1
                                continue

                            try:
                                while True:
                                    ack = await asyncio.wait_for(ws.recv(), timeout=10)
                                    data = json.loads(ack)
                                    if data.get("type") == "message" and data.get("status") == "success":
                                        group_send.success += 1
                                        group_send.latencies.append((time.time() - t0) * 1000)
                                        break
                                    elif data.get("type") == "message" and data.get("groupId"):
                                        continue
                                    elif data.get("type") == "heartbeat":
                                        continue
                                    else:
                                        group_send.fail += 1
                                        break
                            except Exception:
                                group_send.fail += 1

                    sender_tasks.append(send_all_for_user())

            sem_g = asyncio.Semaphore(msg_concurrency)
            async def _bounded(task):
                async with sem_g:
                    await task
            await asyncio.gather(*[_bounded(t) for t in sender_tasks])
            await asyncio.sleep(0.5)

            if r < rounds - 1:
                await asyncio.sleep(0.5)

        group_send.end_time = time.time()
        group_e2e.end_time = time.time()
        await asyncio.sleep(1.0)

        for uid, ws in ws_map.items():
            while True:
                try:
                    raw = await asyncio.wait_for(ws.recv(), timeout=1.5)
                    data = json.loads(raw)
                    if data.get("type") == "message" and data.get("groupId"):
                        group_recv.success += 1
                except asyncio.TimeoutError:
                    break
                except Exception:
                    break

        group_recv.total = group_send.total
        return group_create_lat, group_join_lat, group_send, group_recv, group_e2e

    def _report_section(self, label, *results):
        print(f"\n  {'─' * 60}")
        print(f"  [{label}]")
        for r in results:
            if r.total > 0:
                print(f"    {r.label:20s} {r.stats()}")

    def report_full(self, private_send, private_recv, private_e2e,
                    group_create, group_join, group_send, group_recv, group_e2e,
                    overall_start, overall_end):
        dur = overall_end - overall_start
        print("\n" + "=" * 70)
        print(f"  WeLink Concurrency Stress Test Report")
        print(f"  Total duration: {dur:.1f}s")
        print("=" * 70)
        self._report_section("Setup", self.reg, self.login, self.ws)
        self._report_section("Private Chat", private_send, private_recv, private_e2e)
        self._report_section("Group Setup", group_create, group_join)
        self._report_section("Group Chat", group_send, group_recv, group_e2e)

        total_sent = private_send.success + group_send.success
        if dur > 0:
            print(f"\n  Combined throughput: {total_sent / dur:.1f} msgs/sec")

        priv_rate = private_recv.success / max(1, private_send.success)
        grp_rate = group_recv.success / max(1, group_send.success)
        print(f"  Private delivery: {private_recv.success}/{private_send.success} ({100*priv_rate:.1f}%)")
        print(f"  Group send ok:    {group_send.success}/{group_send.total} ({100*group_send.success/max(1,group_send.total):.1f}%)")
        if group_recv.success > 0:
            avg_per_send = group_recv.success / max(1, group_send.success)
            print(f"  Group fan-out:    {group_recv.success} deliveries ({avg_per_send:.1f}/send)")
        print("=" * 70)


async def main():
    p = argparse.ArgumentParser("WeLink Concurrency Stress Test")
    p.add_argument("-n", "--users", type=int, default=200)
    p.add_argument("--private-pairs", type=int, default=50)
    p.add_argument("--private-rounds", type=int, default=2)
    p.add_argument("--private-msg-per-pair", type=int, default=1)
    p.add_argument("--private-msg-concurrency", type=int, default=20)

    p.add_argument("--group-count", type=int, default=3)
    p.add_argument("--group-size", type=int, default=5)
    p.add_argument("--group-rounds", type=int, default=2)
    p.add_argument("--group-msg-per-member", type=int, default=1)
    p.add_argument("--group-msg-concurrency", type=int, default=15)

    p.add_argument("--http-concurrency", type=int, default=50)
    p.add_argument("--ws-concurrency", type=int, default=30)
    p.add_argument("--prefix", type=str, default="cs")

    p.add_argument("--skip-setup", action="store_true")
    p.add_argument("--private-only", action="store_true")
    p.add_argument("--group-only", action="store_true")
    args = p.parse_args()

    runner = StressRunner()
    overall_start = time.time()

    if not args.skip_setup:
        logged = await runner.register_and_login(args.users, args.prefix, args.http_concurrency)
        if len(logged) < 2:
            print("Too few users.")
            return
        print(f"\n  Connecting {len(logged)} WS...")
        online, ws_map = await runner.batch_ws_connect(logged, args.ws_concurrency)
        print(f"  WS connected: {len(online)}")
    else:
        online = runner.users
        ws_map = {}

    private_only = args.private_only
    group_only = args.group_only
    do_both = not private_only and not group_only
    empty = BenchResult("")

    if private_only or do_both:
        print(f"\n  {'='*60}")
        print(f"  Private Chat: {args.private_pairs} pairs x {args.private_rounds} rounds")
        print(f"  {'='*60}")
        half = len(online) // 2
        pairs = min(half, args.private_pairs)
        ps, pr, pe = await runner.run_private_chat(
            online[:pairs], online[half:half + pairs], ws_map,
            pairs, args.private_msg_concurrency,
            args.private_rounds, args.private_msg_per_pair)
    else:
        ps, pr, pe = empty, empty, empty

    if group_only or do_both:
        print(f"\n  {'='*60}")
        print(f"  Group Chat: {args.group_count} groups x {args.group_size} members")
        print(f"  {'='*60}")
        gc, gj, gs, gr, ge = await runner.run_group_chat(
            online, ws_map, args.group_size, args.group_count,
            args.group_msg_concurrency, args.group_rounds, args.group_msg_per_member)
    else:
        gc, gj, gs, gr, ge = empty, empty, empty, empty, empty

    if ws_map:
        print("\n  Closing WS...")
        for ws in ws_map.values():
            try:
                await ws.close()
            except Exception:
                pass

    overall_end = time.time()
    runner.report_full(ps, pr, pe, gc, gj, gs, gr, ge, overall_start, overall_end)


if __name__ == "__main__":
    asyncio.run(main())
