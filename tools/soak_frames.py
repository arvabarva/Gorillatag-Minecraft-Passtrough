"""Plays Gorilla Tag's side for a while (head poses at 72 Hz, looking around) and prints how many frames Minecraft
shares each second, to catch the frames stopping. Start Minecraft with the passthrough mod first (mc: runPassthroughTest).

python tools/soak_frames.py SECONDS
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
from fake_gorilla import quat_yaw  # noqa: E402

seconds = float(sys.argv[1]) if len(sys.argv) > 1 else 180.0
link = socket.create_connection(("127.0.0.1", 25600), timeout=5)
link.sendall(struct.pack(">iBiffff", 21, 0x81, 7, 72.0, 0.95, 0.55, 0.1))
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
memory = None
start = time.time()
pose_id = 0
last_frame = None
last_print = start
frames_at_print = None
while time.time() - start < seconds:
    tid, x, y, z, _ = state["teleport"]
    yaw = 40.0 * math.sin((time.time() - start) * 0.5)
    qx, qy, qz, qw = quat_yaw(yaw)
    pose_id += 1
    pose = struct.pack(">iidddfffffffdddffbddddddii", pose_id, tid, x, y + 0.62, z, qx, qy, qz, qw,
                       1.54, 1.43, 500.0, x, y, z, yaw, 0.0, 1, x, y + 0.5, z, x, y + 0.5, z, 2205, 2048)
    link.sendall(struct.pack(">iB", len(pose) + 1, 0x82) + pose)
    if memory is None and time.time() - start > 5.0:  # (opening it first would create it, too small for Minecraft)
        try:
            memory = mmap.mmap(-1, 256, tagname="Local\\GorillaPassthroughFrames")
        except OSError:
            memory = None
    now = time.time()
    if memory is not None and now - last_print >= 1.0:
        frame = struct.unpack_from("<i", memory, 20)[0]
        if frames_at_print is not None:
            print("%5.0f s: %3d frames shared in the last second (frame %d)" % (now - start, frame - frames_at_print, frame), flush=True)
        frames_at_print = frame
        last_print = now
    time.sleep(1 / 72)
print("done")
