"""Test helper: moves the test world's player somewhere else by pretending to be a gorilla walking there.

python tools/move_gorilla.py DX DY DZ  (blocks, relative to where the player is now)
"""
import socket
import struct
import sys
import time

link = socket.create_connection(("127.0.0.1", 25600), timeout=5)
link.sendall(struct.pack(">iBif", 9, 0x81, 5, 90.0))
buffer = b""
teleport = None
start = time.time()
link.settimeout(0.2)
while teleport is None and time.time() - start < 10:
    try:
        buffer += link.recv(65536)
    except socket.timeout:
        continue
    while len(buffer) >= 4:
        (length,) = struct.unpack(">i", buffer[:4])
        if len(buffer) < 4 + length:
            break
        body, buffer = buffer[4:4 + length], buffer[4 + length:]
        if body[0] == 0x05:
            teleport = struct.unpack(">idddf", body[1:])
if teleport is None:
    sys.exit("no teleport from Minecraft")
tid, x, y, z, _ = teleport
dx, dy, dz = (float(v) for v in sys.argv[1:4])
for i in range(181):
    f = i / 180.0
    fx, fy, fz = x + dx * f, y + dy * f, z + dz * f
    pose = struct.pack(">iidddfffffffdddffbddddddii", i + 1, tid, fx, fy + 0.62, fz, 0.0, 1.0, 0.0, 0.0,
                       1.4, 1.2, 500.0, fx, fy, fz, 0.0, 0.0, 0, fx, fy + 0.5, fz, fx, fy + 0.5, fz, 1600, 1371)
    link.sendall(struct.pack(">iB", len(pose) + 1, 0x82) + pose)
    time.sleep(1 / 60)
print("moved from (%.1f, %.1f, %.1f) to (%.1f, %.1f, %.1f)" % (x, y, z, x + dx, y + dy, z + dz))
