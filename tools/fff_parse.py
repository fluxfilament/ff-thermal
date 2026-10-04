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


# CameraInfo (record 0x20) fields at exiftool's offsets. Numbers inside a
# record are little-endian whatever the index was - reading them in the
# index's byte order is what made the old shape search come back empty on
# JPEGs from the FLIR ONE app 2.20.1.
CAMERA_INFO = (
    ("Emissivity", 0x20, "f"),
    ("ObjectDistance", 0x24, "f"),
    ("ReflectedApparentTemperature", 0x28, "K"),
    ("AtmosphericTemperature", 0x2C, "K"),
    ("IRWindowTemperature", 0x30, "K"),
    ("IRWindowTransmission", 0x34, "f"),
    ("RelativeHumidity", 0x3C, "f"),
    ("PlanckR1", 0x58, "f"),
    ("PlanckB", 0x5C, "f"),
    ("PlanckF", 0x60, "f"),
    ("AtmosphericTransAlpha1", 0x70, "f"),
    ("AtmosphericTransAlpha2", 0x74, "f"),
    ("AtmosphericTransBeta1", 0x78, "f"),
    ("AtmosphericTransBeta2", 0x7C, "f"),
    ("AtmosphericTransX", 0x80, "f"),
    ("PlanckO", 0x308, "i"),
    ("PlanckR2", 0x30C, "f"),
)


def camera_info(rec: bytes) -> dict:
    """The fields above, plus the model and software strings."""
    info = {}
    for name, at, kind in CAMERA_INFO:
        value = struct.unpack("<" + ("i" if kind == "i" else "f"), rec[at:at + 4])[0]
        info[name] = value
    for name, at, size in (("CameraModel", 0x0d4, 32),
                           ("CameraSoftware", 0x114, 16),
                           ("LensModel", 0x170, 32)):
        info[name] = rec[at:at + size].split(b"\x00")[0].decode("latin-1").strip()
    return info


def main(path):
    data = open(path, "rb").read()
    fff = collect_fff(data)
    print(f"{path}\n  FFF blob: {len(fff)} bytes")
    if not fff:
        return
    order = byte_order(fff)
    print(f"  index byte order: {'big' if order == '>' else 'little'}-endian")
    for main_t, sub_t, off, length in records(fff, order):
        print(f"  record main=0x{main_t:02x} sub=0x{sub_t:02x} off={off} len={length}")
        if main_t != 0x20:  # CameraInfo
            continue
        # The serial number sits at 0x104 and is left out on purpose: it
        # identifies the unit and has no business in a log or a paste.
        for name, value in camera_info(fff[off:off + length]).items():
            if isinstance(value, float) and dict((n, k) for n, _, k in CAMERA_INFO).get(name) == "K":
                print(f"    {name}: {value:.2f} K ({value - 273.15:.2f} C)")
            elif isinstance(value, float):
                print(f"    {name}: {value:.6g}")
            elif value != "":
                print(f"    {name}: {value}")


if __name__ == "__main__":
    for p in sys.argv[1:]:
        main(p)
        print()
