"""Generate simple gradient icon/banner PNGs for Art Home (no external deps)."""
import struct, zlib, sys

def png(path, w, h, pixel):
    raw = b"".join(b"\x00" + b"".join(bytes(pixel(x, y, w, h)) for x in range(w)) for y in range(h))
    def chunk(t, d): return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)
    data = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)) \
        + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")
    open(path, "wb").write(data)

def art(x, y, w, h):
    # warm gallery-wall gradient with a soft "frame"
    fx, fy = x / w, y / h
    inside = 0.18 < fx < 0.82 and 0.2 < fy < 0.8
    if inside:
        return (int(70 + 120 * fx), int(110 + 60 * (1 - fy)), int(160 - 60 * fx))
    return (int(225 - 20 * fy), int(220 - 20 * fy), int(210 - 20 * fy))

out = sys.argv[1]
png(out + "/icon.png", 192, 192, art)
png(out + "/banner.png", 320, 180, art)
