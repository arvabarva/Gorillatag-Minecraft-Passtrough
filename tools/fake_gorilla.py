"""Pretends to be the Gorilla Tag plugin, to test the Minecraft passthrough mod without Gorilla Tag.

Start Minecraft first (cd mc && gradlew runPassthroughTest), then: python tools/fake_gorilla.py [seconds]

It connects to 127.0.0.1:25600, follows Minecraft's teleport, stands still while slowly turning its head (Minecraft
must draw from that head), mines the block under its feet for a moment, and finally saves the newest frame from shared
memory to tools/out/frame.png plus the wrist screen to tools/out/panel.png. Prints what arrived.
"""
import math
import mmap
import os
import socket
import struct
import sys
import threading
import time
import zlib

PORT = 25600
SHM_NAME = "Local\\GorillaPassthroughFrames"
MAX_GRID = 320 * 320
MAX_W, MAX_H = 2560, 2560
SLOTS = 3
HEADER = 256
S_VERTEX = 128
S_INDEX = S_VERTEX + MAX_GRID * 5 * 24
S_COLOR = S_INDEX + MAX_GRID * 18 * 4
SLOT_BYTES = S_COLOR + MAX_W * MAX_H * 4
PANEL = HEADER + SLOTS * SLOT_BYTES
TOTAL = PANEL + 16 + 1280 * 720 * 4

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "out")


class Link:
    def __init__(self):
        self.sock = socket.create_connection(("127.0.0.1", PORT), timeout=5)
        self.sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        self.sock.settimeout(None)
        self.lock = threading.Lock()
        self.counts = {}
        self.teleport = None
        self.sections = {}
        self.entities = 0
        self.status = None
        self.screen = (False, "")
        self.held = None
        threading.Thread(target=self.read_loop, daemon=True).start()

    def send(self, kind, payload=b""):
        frame = struct.pack(">iB", len(payload) + 1, kind) + payload
        with self.lock:
            self.sock.sendall(frame)

    def read_exact(self, n):
        data = b""
        while len(data) < n:
            chunk = self.sock.recv(n - len(data))
            if not chunk:
                raise EOFError
            data += chunk
        return data

    def read_loop(self):
        try:
            while True:
                (length,) = struct.unpack(">i", self.read_exact(4))
                body = self.read_exact(length)
                kind, p = body[0], body[1:]
                self.counts[kind] = self.counts.get(kind, 0) + 1
                if kind == 0x01:
                    version = struct.unpack(">i", p[:4])[0]
                    print("hello: protocol", version)
                elif kind == 0x02:
                    sx, sy, sz = struct.unpack(">iii", p[:12])
                    mask = p[12:12 + 512]
                    (partials,) = struct.unpack(">h", p[524:526])
                    solid = sum(bin(b).count("1") for b in mask)
                    self.sections[(sx, sy, sz)] = (solid, partials)
                elif kind == 0x03:
                    self.sections.pop(struct.unpack(">iii", p[:12]), None)
                elif kind == 0x05:
                    tid, x, y, z, yaw = struct.unpack(">idddf", p)
                    self.teleport = (tid, x, y, z, yaw)
                    print("teleport %d to (%.2f, %.2f, %.2f) yaw %.0f" % (tid, x, y, z, yaw))
                elif kind == 0x06:
                    self.entities = struct.unpack(">h", p[:2])[0]
                elif kind == 0x07:
                    self.status = struct.unpack(">ffiiibbb", p)
                elif kind == 0x09:
                    if p[0] == 0:
                        print("holding: nothing")
                    else:
                        w, h = struct.unpack(">HH", p[1:5])
                        opaque = sum(1 for i in range(w * h) if p[5 + i * 4 + 3] >= 128)
                        print("holding: %s %dx%d, %d opaque pixels" % ("block" if p[0] == 2 else "item", w, h, opaque))
                        self.held = (p[0], w, h, bytes(p[5:5 + w * h * 4]))
                elif kind == 0x08:
                    (n,) = struct.unpack(">H", p[1:3])
                    self.screen = (p[0] != 0, p[3:3 + n].decode("utf-8"))
                    print("menu:", "open" if p[0] else "closed", self.screen[1])
        except (EOFError, OSError):
            print("connection closed")


def quat_yaw(yaw_degrees):
    """Minecraft camera rotation for a yaw (0 = south), level head, as Camera.setRotation builds it."""
    a = math.pi - math.radians(yaw_degrees)
    return (0.0, math.sin(a / 2), 0.0, math.cos(a / 2))


def save_png(path, width, height, rgba_bottom_up):
    rows = []
    stride = width * 4
    for y in range(height - 1, -1, -1):
        rows.append(b"\x00" + rgba_bottom_up[y * stride:(y + 1) * stride])
    raw = b"".join(rows)

    def chunk(tag, data):
        c = struct.pack(">I", len(data)) + tag + data
        return c + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(raw, 6)) + chunk(b"IEND", b"")
    with open(path, "wb") as f:
        f.write(png)


def main():
    seconds = float(sys.argv[1]) if len(sys.argv) > 1 else 12.0
    link = Link()
    link.send(0x81, struct.pack(">i", 1))
    start = time.time()
    while link.teleport is None and time.time() - start < 15:
        time.sleep(0.05)
    if link.teleport is None:
        print("FAIL: no teleport from Minecraft (is a single-player world open?)")
        return 1
    tid, fx, fy, fz, yaw0 = link.teleport

    pose_id = 0
    mined = False
    inventory_opened = False
    menu_seen = False
    start = time.time()
    while time.time() - start < seconds:
        t = time.time() - start
        if link.teleport[0] != tid:
            tid, fx, fy, fz, yaw0 = link.teleport
        # look around at first, then hold still facing the held item
        yaw = yaw0 + (40.0 * math.sin(t * 0.6) if t < 5.0 else 0.0)
        qx, qy, qz, qw = quat_yaw(yaw)
        # right fist 0.35 in front, 0.25 down and 0.18 right of the eyes, pointing forward and 35 degrees up, like a
        # hand holding a sword out (Minecraft: yaw 0 looks south, +z)
        r = math.radians(yaw)
        forward = (-math.sin(r), math.cos(r))
        right = (-math.cos(r), -math.sin(r))
        hx = fx + forward[0] * 0.35 + right[0] * 0.18
        hz = fz + forward[1] * 0.35 + right[1] * 0.18
        hy = fy + 0.62 - 0.25
        tilt = math.radians(35)
        along = (forward[0] * math.cos(tilt), math.sin(tilt), forward[1] * math.cos(tilt))
        pose_id += 1
        payload = struct.pack(">iidddfffffffdddffbddddddii", pose_id, tid, fx, fy + 0.62, fz, qx, qy, qz, qw,
                              1.4, 1.2, 500.0, fx, fy, fz, yaw, 0.0, 1,
                              hx - right[0] * 0.36, hy, hz - right[1] * 0.36, hx, hy, hz, 1600, 1371)
        link.send(0x82, payload)
        if 3.0 < t < 5.0:
            # mine the block under the feet (face 1 = up); the drop lands in the hotbar and shows in the hand
            link.send(0x83, struct.pack(">iiib", math.floor(fx), math.floor(fy) - 1, math.floor(fz), 1))
            mined = True
        # the inventory: open it, take an item and put it in the first hotbar slot with the pointer, close it again
        # (positions are for the creative inventory of the 854x480 test window)
        if 5.5 < t and not inventory_opened:
            link.send(0x8B)
            inventory_opened = True
        if 6.0 < t < 8.0:
            # combat tab, then its first weapon, then the first hotbar slot
            if t < 6.6:
                u, v, pressed = 0.3665, 0.1667, 1 if 6.3 < t < 6.4 else 0
            elif t < 7.2:
                u, v, pressed = 0.310, 0.677, 1 if 6.9 < t < 7.0 else 0
            else:
                u, v, pressed = 0.310, 0.285, 1 if 7.5 < t < 7.6 else 0
            link.send(0x8C, struct.pack(">ffbb", u, v, pressed, 0))
        if 8.0 < t and inventory_opened and link.screen[0] and not menu_seen:
            menu_seen = True
            link.send(0x8B)
        time.sleep(1 / 60)

    screen_open = (menu_seen, "")
    os.makedirs(OUT, exist_ok=True)
    shm = mmap.mmap(-1, TOTAL, tagname=SHM_NAME)
    magic, version, slot_bytes, latest, reader, frame = struct.unpack("<iiiiii", shm[0:24])
    print("shared memory: magic %x version %d slot bytes %d latest %d frames %d" % (magic, version, slot_bytes, latest, frame))
    ok = True
    if latest < 0:
        print("FAIL: Minecraft hasn't shared any frame")
        ok = False
    else:
        base = HEADER + latest * SLOT_BYTES
        seq, pid, w, h, gw, gh, tanh, tanv = struct.unpack("<qiiiiiff", shm[base:base + 36])
        print("frame: seq %d pose %d (sent up to %d) %dx%d grid %dx%d tan %.2fx%.2f" % (seq, pid, pose_id, w, h, gw, gh, tanh, tanv))
        vertices, opaque, back, front = struct.unpack("<iiii", shm[base + 60:base + 76])
        print("mesh: %d vertices, %d plain triangles, %d edge triangles (x2 layers)" % (vertices, opaque // 3, back // 3))
        raw = shm[base + S_VERTEX:base + S_VERTEX + gw * gh * 24]
        grid = []
        for i in range(gw * gh):
            grid.extend(struct.unpack_from("<fff", raw, i * 24))
        dist = [math.sqrt(grid[i] ** 2 + grid[i + 1] ** 2 + grid[i + 2] ** 2) for i in range(0, len(grid), 3)]
        near = sum(1 for d in dist if d < 499)
        print("depth grid: nearest %.2f m, %d of %d points closer than the sky" % (min(dist), near, len(dist)))
        center = (gh // 2) * gw + gw // 2
        print("centre point (unity camera space): (%.2f, %.2f, %.2f)" % tuple(grid[center * 3:center * 3 + 3]))
        color = bytes(shm[base + S_COLOR:base + S_COLOR + w * h * 4])
        save_png(os.path.join(OUT, "frame.png"), w, h, color)
        pseq, pw, ph = struct.unpack("<qii", shm[PANEL:PANEL + 16])
        if pw > 0:
            save_png(os.path.join(OUT, "panel.png"), pw, ph, bytes(shm[PANEL + 16:PANEL + 16 + pw * ph * 4]))
            print("wrist screen: %dx%d" % (pw, ph))
        if pid <= 0 or near == 0:
            ok = False

    solid = sum(s for s, _ in link.sections.values())
    print("messages:", {hex(k): v for k, v in sorted(link.counts.items())})
    print("sections held: %d (%d solid blocks), entities near: %d, status: %s, mined: %s" %
          (len(link.sections), solid, link.entities, link.status, mined))
    print("inventory menu opened and closed:", screen_open[0] and not link.screen[0])
    if link.held:
        kind, w, h, pixels = link.held
        save_png(os.path.join(OUT, "held.png"), w, h, b"".join(pixels[(h - 1 - y) * w * 4:(h - y) * w * 4] for y in range(h)))
    if not link.sections or not screen_open[0]:
        ok = False
    print("PASS" if ok else "FAIL")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
