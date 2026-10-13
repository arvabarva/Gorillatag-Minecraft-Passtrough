"""Test helper: plays a few menu steps as the fake gorilla, then saves the Minecraft window to tools/out/panel.png.

python tools/menu_steps.py STEP [STEP...]
  open           open the inventory (or close a menu)
  click:U,V      left click at U, V (0..1 across the menu, V from the bottom)
  rclick:U,V     right click
  use            use the held item (put armour on, eat...)
  slot:N         move the hotbar selection by N
  wait:S         wait S seconds
"""
import mmap
import os
import socket
import struct
import sys
import threading
import time

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "out")
MAX_GRID = 320 * 320
SLOT_BYTES = 128 + MAX_GRID * 5 * 24 + MAX_GRID * 18 * 4 + 2560 * 2560 * 4
PANEL = 256 + 3 * SLOT_BYTES
TOTAL = PANEL + 16 + 1280 * 720 * 4

link = socket.create_connection(("127.0.0.1", 25600), timeout=5)
link.sendall(struct.pack(">iBif", 9, 0x81, 5, 90.0))
state = {"teleport": None}


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


threading.Thread(target=reader, daemon=True).start()
while state["teleport"] is None:
    time.sleep(0.05)
pose_id = [0]


def send(kind, payload=b""):
    link.sendall(struct.pack(">iB", len(payload) + 1, kind) + payload)


def keep_pose(seconds):
    tid, x, y, z, yaw = state["teleport"]
    end = time.time() + seconds
    while time.time() < end:
        pose_id[0] += 1
        send(0x82, struct.pack(">iidddfffffffdddffbddddddii", pose_id[0], tid, x, y + 0.62, z, 0.0, 1.0, 0.0, 0.0,
                               1.4, 1.2, 500.0, x, y, z, 0.0, 0.0, 1, x, y + 0.5, z, x, y + 0.5, z, 1600, 1371))
        time.sleep(1 / 30)


keep_pose(1.0)
for step in sys.argv[1:]:
    name, _, arg = step.partition(":")
    if name == "open":
        send(0x8B)
    elif name in ("click", "rclick"):
        u, v = (float(a) for a in arg.split(","))
        button = 1 if name == "click" else 2
        send(0x8C, struct.pack(">ffbb", u, v, 0, 0))
        keep_pose(0.15)
        send(0x8C, struct.pack(">ffbb", u, v, button, 0))
        keep_pose(0.1)
        send(0x8C, struct.pack(">ffbb", u, v, 0, 0))
    elif name == "use":
        send(0x86, b"\x01")
        keep_pose(0.2)
        send(0x86, b"\x00")
    elif name == "slot":
        send(0x88, struct.pack(">b", int(arg)))
    elif name == "wait":
        keep_pose(float(arg))
        continue
    keep_pose(0.4)
keep_pose(0.5)
shm = mmap.mmap(-1, TOTAL, tagname="Local\\GorillaPassthroughFrames")
seq, w, h = struct.unpack("<qii", shm[PANEL:PANEL + 16])
pixels = bytes(shm[PANEL + 16:PANEL + 16 + w * h * 4])
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from fake_gorilla import save_png  # noqa: E402

os.makedirs(OUT, exist_ok=True)
save_png(os.path.join(OUT, "panel.png"), w, h, pixels)
print("saved the Minecraft window (%dx%d)" % (w, h))
