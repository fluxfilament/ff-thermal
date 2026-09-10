#!/usr/bin/env python3
"""Pull the FLIR FFF metadata records out of a FLIR One JPEG.

The JPEG carries the FFF blob split across APP1 segments marked "FLIR\0";
they have to be reassembled in chunk order before the record index is
readable. Written because exiftool needs root to install here, and the
Planck coefficients are the one thing that must come off THIS camera.
"""
import struct
import sys


def collect_fff(data: bytes) -> bytes:
    """Reassembles the FFF blob from the APP1 "FLIR\0" chunks, in order."""
    chunks = {}
    i = 2
    while i < len(data) - 4:
        if data[i] != 0xFF:
            i += 1
            continue
        marker = data[i + 1]
        if marker in (0xD8, 0x01) or 0xD0 <= marker <= 0xD7:
            i += 2
            continue
        if marker == 0xDA:  # start of scan: metadata is all behind us
            break
        seg_len = struct.unpack(">H", data[i + 2:i + 4])[0]
        seg = data[i + 4:i + 2 + seg_len]
        if marker == 0xE1 and seg.startswith(b"FLIR\x00"):
            # 1 byte reserved, then chunk number and chunk count
            idx, total = seg[6], seg[7]
            chunks[idx] = seg[8:]
        i += 2 + seg_len
    if not chunks:
        return b""
    return b"".join(chunks[k] for k in sorted(chunks))


def byte_order(fff: bytes) -> str:
    """FFF comes in either endianness; the index header tells them apart.

    A plausible index sits inside the blob and holds a handful of records,
    so the reading that yields absurd numbers is the wrong one. FLIR One
    JPEGs turn out to be big-endian, which is worth stating outright - it
    is the opposite of the little-endian thermal stream on the USB wire.
    """
    for order in (">", "<"):
        off, count = struct.unpack(order + "II", fff[0x18:0x20])
        if 0 < count < 1000 and 0 < off < len(fff) and off + count * 32 <= len(fff):
            return order
    raise ValueError("neither byte order gives a sane record index")


def records(fff: bytes, order: str):
    """Yields (main_type, sub_type, offset, length) from the record index."""
    if not fff.startswith(b"FFF\x00"):
        raise ValueError("not an FFF blob")
    index_off, index_count = struct.unpack(order + "II", fff[0x18:0x20])
    for n in range(index_count):
        at = index_off + n * 32
        main, sub, _ver, _id, off, length = struct.unpack(
            order + "HHIIII", fff[at:at + 20])
        if length:
            yield main, sub, off, length


def find_planck(rec: bytes, order: str):
    """Locates the Planck coefficients by their shape, not by a fixed offset.

    R1, B and F sit consecutively, and their magnitudes are unmistakable:
    R1 in the thousands, B around 1400, F at or near 1. O (a negative
    int32) and R2 (a small float) sit consecutively elsewhere. Matching on
    shape means this does not depend on remembering exiftool's offset table
    correctly, and it reports every candidate rather than the first.
    """
    triples, pairs = [], []
    for off in range(0, len(rec) - 12, 4):
        r1, b, f = struct.unpack(order + "fff", rec[off:off + 12])
        if 1e3 < r1 < 1e6 and 1e3 < b < 2e3 and 0.5 <= f <= 2.0:
            triples.append((off, r1, b, f))
    for off in range(0, len(rec) - 8, 4):
        o = struct.unpack(order + "i", rec[off:off + 4])[0]
        r2 = struct.unpack(order + "f", rec[off + 4:off + 8])[0]
        if -1e5 < o < 0 and 1e-4 < r2 < 1.0:
            pairs.append((off, o, r2))
    return triples, pairs


def main(path):
    data = open(path, "rb").read()
    fff = collect_fff(data)
    print(f"{path}\n  FFF blob: {len(fff)} bytes")
    if not fff:
        return
    order = byte_order(fff)
    print(f"  byte order: {'big' if order == '>' else 'little'}-endian")
    for main_t, sub_t, off, length in records(fff, order):
        print(f"  record main=0x{main_t:02x} sub=0x{sub_t:02x} off={off} len={length}")
        if main_t != 0x20:  # CameraInfo
            continue
        rec = fff[off:off + length]
        triples, pairs = find_planck(rec, order)
        for o, r1, b, f in triples:
            print(f"    R1/B/F candidate @0x{o:03x}: R1={r1:.4f} B={b:.4f} F={f:.4f}")
        for o, oo, r2 in pairs:
            print(f"    O/R2 candidate  @0x{o:03x}: O={oo} R2={r2:.9f}")
        emis, dist, refl = struct.unpack(order + "fff", rec[0x20:0x2c])
        print(f"    Emissivity={emis:.4f} ObjectDistance={dist:.4f} "
              f"ReflectedApparentTemp={refl:.2f} K ({refl - 273.15:.2f} C)")
        for label, at, size in (("CameraModel", 0x0d4, 32),
                                ("CameraPartNumber", 0x0f4, 16),
                                ("CameraSerialNumber", 0x104, 16),
                                ("CameraSoftware", 0x114, 16),
                                ("LensModel", 0x170, 32)):
            s = rec[at:at + size].split(b"\x00")[0].decode("latin-1").strip()
            if s:
                print(f"    {label}: {s}")


if __name__ == "__main__":
    for p in sys.argv[1:]:
        main(p)
        print()
