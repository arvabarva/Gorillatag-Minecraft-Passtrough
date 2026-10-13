"""Multiplayer test with the real Gorilla Tag: plays a friend in the second test Minecraft (mc: runPassthroughTestB,
link port 25601). Walks up next to your gorilla (found among Minecraft's entities by its gorilla size) and sends a
real Gorilla Tag gorilla pose (saved by the plugin in debug mode: BepInEx/gorillapass-pose.txt), so your Gorilla Tag
should draw a real Gorilla Tag gorilla there.

python tools/friend_gorilla.py SECONDS [BLOCKS_AWAY]
"""
import math
import os
import socket
import struct
import sys
import threading
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from fake_gorilla import quat_yaw  # noqa: E402

seconds = float(sys.argv[1]) if len(sys.argv) > 1 else 40.0
POSE_FILE = r"C:\Program Files (x86)\Steam\steamapps\common\Gorilla Tag\BepInEx\gorillapass-pose.txt"

link = socket.create_connection(("127.0.0.1", 25601), timeout=5)
link.sendall(struct.pack(">iBiffff", 21, 0x81, 6, 90.0, 0.95, 0.55, 0.1))
state = {"teleport": None, "gorilla": None}


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
            if body[0] == 0x05:
                state["teleport"] = struct.unpack(">idddf", body[1:])
            elif body[0] == 0x06:
                (count,) = struct.unpack(">h", body[1:3])
                for i in range(count):
                    _, x0, y0, z0, x1, y1, z1 = struct.unpack(">iffffff", body[3 + i * 28:3 + (i + 1) * 28])
                    if 0.75 < y1 - y0 < 0.85:  # a gorilla-sized player: you
                        state["gorilla"] = ((x0 + x1) / 2, y0, (z0 + z1) / 2)


threading.Thread(target=reader, daemon=True).start()
while state["teleport"] is None:
    time.sleep(0.05)
tid = state["teleport"][0]

for _ in range(600):
    if os.path.exists(POSE_FILE) and state["gorilla"] is not None:
        break
    pose_id = 0
    time.sleep(0.1)
feet_line, blob_hex = open(POSE_FILE).read().split("\n")[:2]
fx0, fy0, fz0 = (float(v) for v in feet_line.split())
blob = bytes.fromhex(blob_hex.strip())
rx0, ry0, rz0 = struct.unpack(">ddd", blob[:24])
bones_end = 24 + 16 + 1 + blob[40] * 28
blob = bytearray(blob[:bones_end])  # without the time it was taken: each pose gets a new one
if "--arm" in sys.argv:
    # a pose that isn't yours: the right shoulder (bone 7) turned a quarter round, so you can tell the two apart
    at = 41 + 7 * 28 + 12
    qx, qy, qz, qw = struct.unpack(">ffff", blob[at:at + 16])
    s = math.sqrt(0.5)  # 90 degrees about x
    blob[at:at + 16] = struct.pack(">ffff", s * (qw + qx), s * (qy + qz), s * (qz - qy), s * (qw - qx))
blob = bytes(blob)
gx, gy, gz = state["gorilla"] or (fx0, fy0, fz0)  # not seen from here: where your gorilla's feet were
away = float(sys.argv[2]) if len(sys.argv) > 2 else 1.0
x, y, z = gx + away, gy, gz  # east of you
yaw = 90.0
print("friend standing next to you at (%.1f, %.1f, %.1f)" % (x, y, z))

pose_id = 0
start = time.time()
while time.time() - start < seconds:
    tid = state["teleport"][0]
    qx, qy, qz, qw = quat_yaw(yaw)
    pose_id += 1
    pose = struct.pack(">iidddfffffffdddffbddddddii", pose_id, tid, x, y + 0.62, z, qx, qy, qz, qw,
                       1.4, 1.2, 500.0, x, y, z, yaw, 0.0, 1, x, y + 0.5, z, x, y + 0.5, z, 1280, 1097)
    link.sendall(struct.pack(">iB", len(pose) + 1, 0x82) + pose)
    if pose_id % 3 == 0:
        # the real gorilla pose, moved to where the friend stands
        rig = (struct.pack(">ddd", x + (rx0 - fx0), y + (ry0 - fy0), z + (rz0 - fz0)) + blob[24:]
               + struct.pack(">d", time.time() - start))
        link.sendall(struct.pack(">iB", len(rig) + 1, 0x8D) + rig)
    time.sleep(1 / 60)
print("friend done")
