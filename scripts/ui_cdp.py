#!/usr/bin/env python3
"""Runs JavaScript in Eclipse's page (emulator tests) through the Chrome
DevTools protocol, and prints the result.

    ui_cdp.py <local port forwarded to the WebView> '<expression>'

The accessibility tree that uiautomator reads lags behind web pages on some
Android versions; the page itself is always right. Python's standard
library only (minimal WebSocket client)."""
import base64
import json
import os
import socket
import struct
import sys
import urllib.request

port, expr = int(sys.argv[1]), sys.argv[2]
pages = json.load(urllib.request.urlopen(f"http://127.0.0.1:{port}/json", timeout=10))
page = next((p for p in pages if p.get("type") == "page"), None)
if not page:
    sys.exit("no page")
path = page["webSocketDebuggerUrl"].split(f":{port}", 1)[1] if f":{port}" in page["webSocketDebuggerUrl"] else "/" + page["webSocketDebuggerUrl"].split("/", 3)[3]

s = socket.create_connection(("127.0.0.1", port), timeout=20)
key = base64.b64encode(os.urandom(16)).decode()
s.sendall((f"GET {path} HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
           f"Sec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n").encode())
resp = b""
while b"\r\n\r\n" not in resp:
    chunk = s.recv(4096)
    if not chunk:
        sys.exit("handshake failed")
    resp += chunk
if b" 101 " not in resp.split(b"\r\n", 1)[0]:
    sys.exit("handshake refused: " + resp.split(b"\r\n", 1)[0].decode())
buf = resp.split(b"\r\n\r\n", 1)[1]


def send(obj):
    data = json.dumps(obj).encode()
    mask = os.urandom(4)
    head = bytes([0x81])
    n = len(data)
    if n < 126:
        head += bytes([0x80 | n])
    elif n < 65536:
        head += bytes([0x80 | 126]) + struct.pack(">H", n)
    else:
        head += bytes([0x80 | 127]) + struct.pack(">Q", n)
    s.sendall(head + mask + bytes(b ^ mask[i % 4] for i, b in enumerate(data)))


def read_exact(n):
    global buf
    while len(buf) < n:
        chunk = s.recv(65536)
        if not chunk:
            raise EOFError
        buf += chunk
    out, buf = buf[:n], buf[n:]
    return out


def recv():
    msg = b""
    while True:
        b0, b1 = read_exact(2)
        n = b1 & 0x7F
        if n == 126:
            n = struct.unpack(">H", read_exact(2))[0]
        elif n == 127:
            n = struct.unpack(">Q", read_exact(8))[0]
        if b1 & 0x80:
            read_exact(4)
        msg += read_exact(n)
        if b0 & 0x80:
            return msg


send({"id": 1, "method": "Runtime.evaluate", "params": {"expression": expr, "returnByValue": True, "awaitPromise": True}})
while True:
    m = json.loads(recv())
    if m.get("id") == 1:
        r = m.get("result", {})
        if "exceptionDetails" in r:
            sys.exit("error: " + json.dumps(r["exceptionDetails"])[:300])
        v = r.get("result", {}).get("value")
        print(json.dumps(v) if not isinstance(v, str) else v)
        break
