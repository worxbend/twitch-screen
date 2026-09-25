#!/usr/bin/env python3
"""Twitch-flavored dummy server, protocol v2 (persistent TCP push).

Simulates a live stream: pushes "stats" frames every 5 s (viewers,
followers, subs, uptime, chat rate) and random stream events
(follow / chat / sub / gift / raid / bits) as "notify" frames.

Usage:  python3 twitch_server.py [--port 8099] [--trigger-port 8098]
"""

import argparse
import json
import random
import socket
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

MAX_BUFFER = 64
STATS_INTERVAL_S = 5
PING_INTERVAL_S = 30

NAMES = ["nova_kitsune", "xX_shadow_Xx", "mike_p", "kappachungus", "luna_gg",
         "criticalhit_tv", "pixel_anna", "dj_volt", "sneakybob", "mira_plays",
         "cheerdude", "retro_ralph", "queenoflag", "t0xic_waste", "frostbyte_"]

CHAT_LINES = ["POGGIES that play!", "LET'S GOOO", "how did you do that??",
              "clip it!", "lol", "first time here, great stream", "W streamer",
              "that was clean", "gg", "tutorial when?"]

lock = threading.Lock()
notifications = []  # ascending seq, ring buffer
latest_seq = 0

stats = {"live": True, "viewers": 842, "followers": 12400,
         "subs": 318, "uptime_s": 3660, "chat_rate": 35, "msg_total": 2135}

clients = {}  # conn -> send_lock
clients_lock = threading.Lock()


def send_frame(conn, frame):
    data = (json.dumps(frame) + "\n").encode()
    with clients[conn]:
        conn.sendall(data)


def broadcast(frame):
    dead = []
    with clients_lock:
        conns = list(clients)
    for c in conns:
        try:
            send_frame(c, frame)
        except OSError:
            dead.append(c)
    for c in dead:
        drop_client(c, "send failed")


def make_notification():
    global latest_seq
    kind, title, body = random.choices(
        [
            ("follow", "New follower", "{n} followed"),
            ("chat", "Chat highlight", "{n}: {c}"),
            ("sub", "New sub", "{n} subscribed - Tier 1 ({m} months)"),
            ("gift", "Gift subs", "{n} gifted {g} subs"),
            ("raid", "Incoming raid", "{n} is raiding with {g} viewers!"),
            ("bits", "Cheer", "{n} cheered {b} bits"),
        ],
        weights=[28, 30, 14, 8, 9, 11], k=1,
    )[0]
    body = body.format(n=random.choice(NAMES), c=random.choice(CHAT_LINES),
                       m=random.randint(1, 36), g=random.choice([2, 5, 5, 10, 20, 87]),
                       b=random.choice([100, 100, 500, 1000]))
    with lock:
        latest_seq += 1
        ntf = {"seq": latest_seq, "id": f"evt-{latest_seq:04d}", "type": kind,
               "title": title, "body": body,
               "timestamp": int(time.time()), "ttl_ms": 30000}
        notifications.append(ntf)
        del notifications[:-MAX_BUFFER]
    broadcast({"op": "notify", **ntf})
    return ntf


def walk_stats():
    with lock:
        s = dict(stats)
        s["viewers"] = max(3, s["viewers"] + random.randint(-12, 15))
        if random.random() < 0.35:
            s["followers"] += random.randint(0, 3)
        if random.random() < 0.15:
            s["subs"] += random.randint(0, 1)
        s["chat_rate"] = max(2, min(120, s["chat_rate"] + random.randint(-9, 10)))
        s["uptime_s"] += STATS_INTERVAL_S
        s["msg_total"] += round(s["chat_rate"] * STATS_INTERVAL_S / 60)
        stats.update(s)
        return dict(stats)


def drop_client(conn, reason):
    with clients_lock:
        gone = clients.pop(conn, None) is not None
    try:
        conn.close()
    except OSError:
        pass
    if gone:
        print(f"[drop] {conn.getpeername()} ({reason})", flush=True)


def handle_client(conn, addr):
    conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    conn.setsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)
    conn.settimeout(120)
    with clients_lock:
        clients[conn] = threading.Lock()
    print(f"[accept] {addr}", flush=True)
    buf = b""
    try:
        while True:
            chunk = conn.recv(4096)
            if not chunk:
                break
            buf += chunk
            while b"\n" in buf:
                line, buf = buf.split(b"\n", 1)
                if line.strip():
                    handle_frame(conn, addr, line)
    except OSError as e:
        print(f"[drop] {addr} ({e})", flush=True)
    finally:
        drop_client(conn, "eof")


def handle_frame(conn, addr, line):
    try:
        frame = json.loads(line)
    except json.JSONDecodeError:
        print(f"[bad] {addr} {line[:80]!r}", flush=True)
        return

    op = frame.get("op")
    if op == "hello":
        device = frame.get("device", "?")
        try:
            last_seq = int(frame.get("last_seq", 0))
        except (TypeError, ValueError):
            last_seq = 0
        with lock:
            latest = latest_seq
            replay = [n for n in notifications if 0 < last_seq < n["seq"]]
            snap = dict(stats)
        send_frame(conn, {"op": "welcome", "proto": 2,
                          "latest_seq": latest, "server_time": int(time.time())})
        send_frame(conn, {"op": "stats", **snap})  # immediate state on join
        for n in replay:
            send_frame(conn, {"op": "notify", **n})
        print(f"[hello] {addr} device={device} last_seq={last_seq} "
              f"-> welcome latest={latest}, replay {len(replay)}", flush=True)
    elif op == "pong":
        pass
    elif op == "ping":
        send_frame(conn, {"op": "pong", "t": frame.get("t", 0)})
    else:
        print(f"[bad] {addr} unknown op {op!r}", flush=True)


def generator_loop(min_s=6, max_s=18):
    while True:
        time.sleep(random.uniform(min_s, max_s))
        n = make_notification()
        print(f"[gen] #{n['seq']} {n['type']}: {n['title']} — {n['body']}", flush=True)


def stats_loop():
    while True:
        time.sleep(STATS_INTERVAL_S)
        broadcast({"op": "stats", **walk_stats()})


def ping_loop():
    while True:
        time.sleep(PING_INTERVAL_S)
        broadcast({"op": "ping", "t": int(time.time())})


class TriggerHandler(BaseHTTPRequestHandler):
    def do_POST(self):
        if urlparse(self.path).path == "/trigger":
            n = make_notification()
            print(f"[trigger] #{n['seq']} {n['type']}: {n['title']}", flush=True)
            body = json.dumps(n).encode()
            self.send_response(201)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        else:
            self.send_error(404)

    def log_message(self, *args):
        pass


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8099)
    ap.add_argument("--trigger-port", type=int, default=8098)
    args = ap.parse_args()

    threading.Thread(target=generator_loop, daemon=True).start()
    threading.Thread(target=stats_loop, daemon=True).start()
    threading.Thread(target=ping_loop, daemon=True).start()
    threading.Thread(
        target=lambda: ThreadingHTTPServer(
            ("0.0.0.0", args.trigger_port), TriggerHandler).serve_forever(),
        daemon=True).start()

    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("0.0.0.0", args.port))
    srv.listen(8)
    print(f"twitch dummy server on tcp://0.0.0.0:{args.port} "
          f"(trigger: http POST :{args.trigger_port}/trigger)", flush=True)
    while True:
        conn, addr = srv.accept()
        threading.Thread(target=handle_client, args=(conn, addr), daemon=True).start()


if __name__ == "__main__":
    main()
