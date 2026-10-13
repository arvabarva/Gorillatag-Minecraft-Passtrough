"""Saves what Minecraft last shared with Gorilla Tag: the colour picture, its near/far alpha mask and the background
picture, as PNGs in tools/out/. Run while the passthrough is running (Minecraft must be linked).

python tools/dump_frame.py
"""
import mmap
import os
import struct
import zlib

MAX_GRID = 320 * 320
MAX_VERTICES = MAX_GRID * 5
MAX_INDICES = MAX_GRID * 18
S_VERTEX_DATA = 128
S_INDEX_DATA = S_VERTEX_DATA + MAX_VERTICES * 24
S_COLOR_DATA = S_INDEX_DATA + MAX_INDICES * 4
S_BG_DATA = S_COLOR_DATA + 2560 * 2560 * 4
HEADER = 256
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "out")


def png(path, width, height, rows):
    """rows: list of bytes, RGBA, top row first."""
    raw = b"".join(b"\x00" + r for r in rows)

    def chunk(kind, data):
        c = struct.pack(">I", len(data)) + kind + data
        return c + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n")
        f.write(chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)))
        f.write(chunk(b"IDAT", zlib.compress(raw, 6)))
        f.write(chunk(b"IEND", b""))


memory = mmap.mmap(-1, HEADER, tagname="Local\\GorillaPassthroughFrames")
slot_bytes, latest = struct.unpack_from("<ii", memory, 8)
version = struct.unpack_from("<i", memory, 4)[0]
memory.close()
memory = mmap.mmap(-1, HEADER + 3 * slot_bytes, tagname="Local\\GorillaPassthroughFrames")
base = HEADER + latest * slot_bytes
width, height = struct.unpack_from("<ii", memory, base + 12)
grid_w, grid_h = struct.unpack_from("<ii", memory, base + 20)
counts = struct.unpack_from("<iiii", memory, base + 60)
print("version", version, "slot", latest, "picture", width, "x", height, "grid", grid_w, "x", grid_h,
      "vertices/opaque/back/front", counts)
os.makedirs(OUT, exist_ok=True)

color = memory[base + S_COLOR_DATA: base + S_COLOR_DATA + width * height * 4]
rows = [color[y * width * 4:(y + 1) * width * 4] for y in range(height - 1, -1, -1)]
opaque = []
for r in rows:
    row = bytearray(r)
    row[3::4] = b"\xff" * width
    opaque.append(bytes(row))
png(os.path.join(OUT, "frame-color.png"), width, height, opaque)
png(os.path.join(OUT, "frame-alpha.png"), width, height, [bytes(v for a in r[3::4] for v in (a, a, a, 255)) for r in rows])
if version >= 7:
    bg_w, bg_h = struct.unpack_from("<ii", memory, base + 76)
    bg = memory[base + S_BG_DATA: base + S_BG_DATA + bg_w * bg_h * 4]
    png(os.path.join(OUT, "frame-background.png"), bg_w, bg_h,
        [bg[y * bg_w * 4:(y + 1) * bg_w * 4] for y in range(bg_h - 1, -1, -1)])
print("saved to", OUT)
