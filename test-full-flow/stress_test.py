import asyncio
import json
import time
import sys
import random
from dataclasses import dataclass, field
from datetime import datetime
from typing import Optional

import aiohttp
import websockets
from websockets.asyncio.client import ClientConnection


BASE_URL = "http://localhost:8080"
WS_URL = "ws://localhost:8081/ws"
API_PREFIX = "/api/v1"


@dataclass
class User:
    username: str
    password: str
    nickname: str
    user_id: int = 0
    access_token: str = ""
    refresh_token: str = ""


@dataclass
class Stats:
    total_register: int = 0
    register_ok: int = 0
    register_fail: int = 0
    register_latency: list = field(default_factory=list)

    total_login: int = 0
    login_ok: int = 0
    login_fail: int = 0
    login_latency: list = field(default_factory=list)

    total_ws_connect: int = 0
    ws_connect_ok: int = 0
    ws_connect_fail: int = 0

    total_send: int = 0
    send_ok: int = 0
    send_fail: int = 0
    send_latency: list = field(default_factory=list)

    total_receive: int = 0
    receive_ok: int = 0
    receive_miss: int = 0
    receive_latency: list = field(default_factory=list)

    start_time: float = 0.0
    end_time: float = 0.0

    def report(self):
        duration = self.end_time - self.start_time
        print("\n" + "=" * 70)
        print("  WeLink Stress Test Report")
        print("=" * 70)
        print(f"  Duration: {duration:.2f}s")
        print()

        print(f"  [Register] total={self.total_register} ok={self.register_ok} fail={self.register_fail}")
        if self.register_latency:
            self._print_latency("register", self.register_latency)
        print(f"  [Login]    total={self.total_login} ok={self.login_ok} fail={self.login_fail}")
        if self.login_latency:
            self._print_latency("login", self.login_latency)
        print(f"  [WebSocket] total={self.total_ws_connect} ok={self.ws_connect_ok} fail={self.ws_connect_fail}")
        print(f"  [Send]     total={self.total_send} ok={self.send_ok} fail={self.send_fail}")
        if self.send_latency:
            self._print_latency("send", self.send_latency)
        print(f"  [Receive]  total={self.total_receive} ok={self.receive_ok} miss={self.receive_miss}")
        if self.receive_latency:
            self._print_latency("receive (e2e)", self.receive_latency)

        if self.send_ok > 0:
            qps = self.send_ok / duration
            print(f"\n  Throughput: {qps:.1f} msgs/sec")
        if self.receive_latency:
            print(f"  Message delivery rate: {self.receive_ok}/{self.total_send} ({100*self.receive_ok/max(1,self.total_send):.1f}%)")
        print("=" * 70)

    def _print_latency(self, label, latencies):
        sorted_lat = sorted(latencies)
        n = len(sorted_lat)
        p50 = sorted_lat[n // 2]
        p90 = sorted_lat[int(n * 0.9)]
        p99 = sorted_lat[int(n * 0.99)]
        print(f"    {label} latency: avg={sum(latencies)/n:.1f}ms p50={p50:.1f}ms p90={p90:.1f}ms p99={p99:.1f}ms")


stats = Stats()


def generate_users(count: int, prefix: str = "stress") -> list[User]:
    users = []
    for i in range(count):
        username = f"{prefix}_{i}_{random.randint(10000, 99999)}"
        users.append(User(
            username=username,
            password="test123",
            nickname=f"Stress{i}"
        ))
    return users


async def register_user(session: aiohttp.ClientSession, user: User, sem: asyncio.Semaphore) -> bool:
    async with sem:
        t0 = time.time()
        try:
            async with session.post(
                f"{BASE_URL}{API_PREFIX}/auth/register",
                json={"username": user.username, "password": user.password, "nickname": user.nickname}
            ) as resp:
                body = await resp.json()
                elapsed = (time.time() - t0) * 1000
                stats.register_latency.append(elapsed)
                if resp.status == 200 and body.get("code") == 200:
                    stats.register_ok += 1
                    return True
                else:
                    stats.register_fail += 1
                    print(f"  [REGISTER FAIL] {user.username}: {body.get('message', body)}")
                    return False
        except Exception as e:
            elapsed = (time.time() - t0) * 1000
            stats.register_latency.append(elapsed)
            stats.register_fail += 1
            print(f"  [REGISTER ERROR] {user.username}: {e}")
            return False


async def login_user(session: aiohttp.ClientSession, user: User, sem: asyncio.Semaphore) -> bool:
    async with sem:
        t0 = time.time()
        try:
            async with session.post(
                f"{BASE_URL}{API_PREFIX}/auth/login",
                json={"username": user.username, "password": user.password}
            ) as resp:
                body = await resp.json()
                elapsed = (time.time() - t0) * 1000
                stats.login_latency.append(elapsed)
                if resp.status == 200 and body.get("code") == 200:
                    data = body.get("data", {})
                    user.access_token = data.get("accessToken", "")
                    user.refresh_token = data.get("refreshToken", "")
                    user_info = data.get("userInfo", {})
                    user.user_id = user_info.get("id", 0)
                    stats.login_ok += 1
                    return True
                else:
                    stats.login_fail += 1
                    return False
        except Exception as e:
            elapsed = (time.time() - t0) * 1000
            stats.login_latency.append(elapsed)
            stats.login_fail += 1
            return False


async def register_and_login_batch(users: list[User], concurrency: int = 50) -> list[User]:
    sem = asyncio.Semaphore(concurrency)
    connector = aiohttp.TCPConnector(limit=concurrency, limit_per_host=concurrency)

    async with aiohttp.ClientSession(connector=connector) as session:
        print(f"\n  Registering {len(users)} users (concurrency={concurrency})...")
        stats.total_register = len(users)
        tasks = [register_user(session, u, sem) for u in users]
        results = await asyncio.gather(*tasks, return_exceptions=True)

        registered = [u for u, ok in zip(users, results) if ok is True]
        print(f"  Registered: {len(registered)}/{len(users)}")
        if not registered:
            print("  ERROR: No users registered successfully!")
            return []

        print(f"\n  Logging in {len(registered)} users (concurrency={concurrency})...")
        stats.total_login = len(registered)
        tasks = [login_user(session, u, sem) for u in registered]
        results = await asyncio.gather(*tasks, return_exceptions=True)

        logged_in = [u for u, ok in zip(registered, results) if ok is True]
        print(f"  Logged in: {len(logged_in)}/{len(registered)}")
        return logged_in


async def connect_ws(user: User, sem: asyncio.Semaphore) -> Optional[ClientConnection]:
    async with sem:
        stats.total_ws_connect += 1
        try:
            ws = await websockets.connect(WS_URL, ping_interval=None, close_timeout=5)
            auth_msg = json.dumps({
                "type": "auth",
                "token": user.access_token,
                "deviceId": "python-stress"
            })
            await ws.send(auth_msg)

            response = await asyncio.wait_for(ws.recv(), timeout=10)
            data = json.loads(response)
            if data.get("type") == "auth" and data.get("status") == "success":
                stats.ws_connect_ok += 1
                return ws
            else:
                stats.ws_connect_fail += 1
                await ws.close()
                return None
        except Exception:
            stats.ws_connect_fail += 1
            return None


async def send_and_verify(
    sender_ws: ClientConnection,
    sender_user: User,
    receiver_ws: ClientConnection,
    receiver_user: User,
    msg_content: str
) -> dict:
    result = {"success": False, "send_latency": 0, "e2e_latency": 0}

    msg_id = f"stress-{random.randint(100000, 999999)}"
    send_msg = json.dumps({
        "type": "message",
        "toUserId": receiver_user.user_id,
        "msgId": msg_id,
        "content": msg_content,
        "msgType": 1
    })

    t_send = time.time()
    try:
        await sender_ws.send(send_msg)
    except Exception as e:
        return result

    try:
        response = await asyncio.wait_for(sender_ws.recv(), timeout=10)
        t_ack = time.time()
        data = json.loads(response)
        if data.get("type") == "message" and data.get("status") == "success":
            result["send_latency"] = (t_ack - t_send) * 1000
            result["send_msg_id"] = data.get("data", msg_id)
        else:
            return result
    except Exception:
        return result

    try:
        while True:
            response = await asyncio.wait_for(receiver_ws.recv(), timeout=15)
            t_recv = time.time()
            data = json.loads(response)
            if data.get("type") == "message" and "msgId" in data:
                result["success"] = True
                result["e2e_latency"] = (t_recv - t_send) * 1000
                result["received_msg_id"] = data.get("msgId")
                break
            elif data.get("type") == "heartbeat":
                continue
    except Exception:
        pass

    return result


async def stress_test(
    user_count: int = 100,
    pair_count: int = 50,
    connect_concurrency: int = 20,
    send_concurrency: int = 10,
    rounds: int = 1
):
    stats.start_time = time.time()

    print("=" * 70)
    print("  WeLink IM Stress Test")
    print(f"  Users: {user_count} | Pairs: {pair_count} | Rounds: {rounds}")
    print(f"  API: {BASE_URL} | WebSocket: {WS_URL}")
    print("=" * 70)

    users = generate_users(user_count)
    logged_in = await register_and_login_batch(users, concurrency=50)
    if len(logged_in) < 2:
        print("  Not enough logged-in users, aborting.")
        return

    users_a = logged_in[0:pair_count]
    users_b = logged_in[pair_count:pair_count*2]
    if len(users_b) < pair_count:
        users_a = users_a[:len(users_b)]
    actual_pairs = len(users_a)
    print(f"\n  Connecting {actual_pairs * 2} WebSocket sessions...")
    sem_connect = asyncio.Semaphore(connect_concurrency)

    all_ws = []
    for u in users_a + users_b:
        ws = await connect_ws(u, sem_connect)
        all_ws.append(ws)

    ws_map = {}
    for u, ws in zip(users_a + users_b, all_ws):
        if ws:
            ws_map[u.user_id] = ws

    connected_pairs = []
    for a, b, wa, wb in zip(users_a, users_b,
                            all_ws[0:actual_pairs],
                            all_ws[actual_pairs:actual_pairs*2]):
        if wa and wb:
            connected_pairs.append((a, wa, b, wb))

    print(f"  Connected: {len(all_ws)}/{actual_pairs*2}, valid pairs: {len(connected_pairs)}")
    if not connected_pairs:
        print("  No valid pairs, aborting.")
        for ws in all_ws:
            if ws:
                await ws.close()
        return

    print(f"\n  Running {rounds} round(s) of message send/receive...")
    for r in range(rounds):
        print(f"\n  --- Round {r+1}/{rounds} ---")
        sem_send = asyncio.Semaphore(send_concurrency)

        async def send_one(pair):
            a, wa, b, wb = pair
            content = f"[Round{r+1}] Hello from {a.username} at {datetime.now().isoformat()}"
            return await send_and_verify(wa, a, wb, b, content)

        tasks = [send_one(p) for p in connected_pairs]
        results = await asyncio.gather(*tasks, return_exceptions=True)

        round_send_ok = 0
        round_recv_ok = 0
        for res in results:
            if isinstance(res, dict):
                stats.total_send += 1
                if res.get("send_latency", 0) > 0:
                    stats.send_ok += 1
                    stats.send_latency.append(res["send_latency"])
                    round_send_ok += 1
                else:
                    stats.send_fail += 1
                stats.total_receive += 1
                if res.get("success"):
                    stats.receive_ok += 1
                    stats.receive_latency.append(res["e2e_latency"])
                    round_recv_ok += 1
                else:
                    stats.receive_miss += 1

        print(f"    Sent OK: {round_send_ok} | Received OK: {round_recv_ok}")

        if r < rounds - 1:
            await asyncio.sleep(1)

    print("\n  Closing connections...")
    for ws in all_ws:
        if ws:
            try:
                await ws.close()
            except Exception:
                pass

    stats.end_time = time.time()
    stats.report()


def main():
    import argparse
    parser = argparse.ArgumentParser(description="WeLink Stress Test")
    parser.add_argument("-n", "--users", type=int, default=100, help="Number of users to register (default: 100)")
    parser.add_argument("-p", "--pairs", type=int, default=50, help="Number of user pairs (default: 50)")
    parser.add_argument("-c", "--connect-concurrency", type=int, default=20, help="WS connect concurrency (default: 20)")
    parser.add_argument("-s", "--send-concurrency", type=int, default=10, help="Send concurrency (default: 10)")
    parser.add_argument("-r", "--rounds", type=int, default=1, help="Number of message rounds (default: 1)")
    parser.add_argument("--register-only", action="store_true", help="Only register users, skip message testing")
    parser.add_argument("--skip-register", action="store_true", help="Skip registration (users already exist)")
    args = parser.parse_args()

    asyncio.run(stress_test(
        user_count=args.users,
        pair_count=args.pairs,
        connect_concurrency=args.connect_concurrency,
        send_concurrency=args.send_concurrency,
        rounds=args.rounds
    ))


if __name__ == "__main__":
    main()
