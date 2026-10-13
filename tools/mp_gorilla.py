"""Multiplayer test: two fake gorillas in two Minecrafts on one PC (see mc/build.gradle: passthroughTest and
passthroughTestB). They stand three blocks apart, face each other with both hands raised, and each saves what its
Minecraft drew to tools/out/mp-<role>.png: you should see the other player as a gorilla reaching up.

python tools/mp_gorilla.py b SECONDS   (start first: the second player, link port 25601)
python tools/mp_gorilla.py a SECONDS   (the host, link port 25600; walks over next to b)
"""
import math
import mmap
import os
import socket
import struct
import sys
import threading
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from fake_gorilla import save_png, quat_yaw  # noqa: E402

role = sys.argv[1]
seconds = float(sys.argv[2]) if len(sys.argv) > 2 else 15.0
port = 25600 if role == "a" else 25601
shm_name = "Local\\GorillaPassthroughFrames" + ("" if role == "a" else "B")
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "out")
MEET = os.path.join(OUT, "mp-b-position.txt")
MAX_GRID = 320 * 320
S_COLOR = 128 + MAX_GRID * 5 * 24 + MAX_GRID * 18 * 4
SLOT_BYTES = S_COLOR + 2560 * 2560 * 4
TOTAL = 256 + 3 * SLOT_BYTES + 16 + 1280 * 720 * 4
os.makedirs(OUT, exist_ok=True)

link = socket.create_connection(("127.0.0.1", port), timeout=5)
fur = (0.2, 0.4, 0.9) if role == "a" else (0.9, 0.5, 0.1)
link.sendall(struct.pack(">iBiffff", 21, 0x81, 5, 90.0, *fur))
state = {"teleport": None, "count": {}}


def reader():
    buffer = b""
    while True:
        try:
            chunk = link.recv(1 << 16)
        except OSError:
            return
        if not chunk:
            return
        buffer += chunk
        while len(buffer) >= 4:
            (length,) = struct.unpack(">i", buffer[:4])
            if len(buffer) < 4 + length:
                break
            body, buffer = buffer[4:4 + length], buffer[4 + length:]
            state["count"][body[0]] = state["count"].get(body[0], 0) + 1
            if body[0] == 0x05:
                state["teleport"] = struct.unpack(">idddf", body[1:])


threading.Thread(target=reader, daemon=True).start()
while state["teleport"] is None:
    time.sleep(0.05)
tid, x, y, z, _ = state["teleport"]
if role == "b":
    with open(MEET, "w") as f:
        f.write("%f %f %f" % (x, y, z))
    yaw = -90.0  # east, towards a
else:
    for _ in range(100):
        if os.path.exists(MEET):
            break
        time.sleep(0.1)
    bx, by, bz = (float(v) for v in open(MEET).read().split())
    x, y, z = bx + 3.0, by, bz  # three blocks east of b
    yaw = 90.0  # west, towards b
print(role, "standing at (%.1f, %.1f, %.1f)" % (x, y, z))

pose_id = 0
start = time.time()
while time.time() - start < seconds:
    t = time.time() - start
    if state["teleport"][0] != tid and role == "b":
        tid, x, y, z, _ = state["teleport"]
    tid = state["teleport"][0]
    qx, qy, qz, qw = quat_yaw(yaw)
    r = math.radians(yaw)
    fwd = (-math.sin(r), math.cos(r))
    right = (-math.cos(r), -math.sin(r))
    lift = 0.25 * math.sin(t * 2.0)  # wave the hands up and down
    lx, lz = x + fwd[0] * 0.3 - right[0] * 0.35, z + fwd[1] * 0.3 - right[1] * 0.35
    rx, rz = x + fwd[0] * 0.3 + right[0] * 0.35, z + fwd[1] * 0.3 + right[1] * 0.35
    pose_id += 1
    pose = struct.pack(">iidddfffffffdddffbddddddii", pose_id, tid, x, y + 0.62, z, qx, qy, qz, qw,
                       1.4, 1.2, 500.0, x, y, z, yaw, 0.0, 1,
                       lx, y + 1.0 + lift, lz, rx, y + 1.0 - lift, rz, 1600, 1371)
    link.sendall(struct.pack(">iB", len(pose) + 1, 0x82) + pose)
    time.sleep(1 / 60)

shm = mmap.mmap(-1, TOTAL, tagname=shm_name)
latest = struct.unpack("<i", shm[12:16])[0]
base = 256 + latest * SLOT_BYTES
w, h = struct.unpack("<ii", shm[base + 12:base + 20])
save_png(os.path.join(OUT, "mp-%s.png" % role), w, h, bytes(shm[base + S_COLOR:base + S_COLOR + w * h * 4]))
print(role, "saved its view; messages:", {hex(k): v for k, v in sorted(state["count"].items())})
