#!/usr/bin/env python3
"""RidgeRendererTest が出した生の画素を PNG にする。

    ./gradlew :app:testDebugUnitTest --tests '*RidgeRendererTest*'   # 中身を出す
    python3 tools/render-ridge.py                                    # 絵にする

**実機の見え方ではない。** グラスは緑 8 階調で黒が透明なので、
ここでは黒を背景として描いている（実際は素通しで、その向こうに本物の山がある）。
"""

import pathlib
import struct
import sys
import zlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = ROOT / "samples" / "kmp" / "app" / "build" / "ridge"


def png(width: int, height: int, rgb: bytes) -> bytes:
    def chunk(kind: bytes, body: bytes) -> bytes:
        return struct.pack(">I", len(body)) + kind + body + struct.pack(">I", zlib.crc32(kind + body))

    raw = bytearray()
    for y in range(height):
        raw.append(0)  # フィルタなし
        raw += rgb[y * width * 3:(y + 1) * width * 3]
    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(bytes(raw), 9))
        + chunk(b"IEND", b"")
    )


def main() -> None:
    files = sorted(SRC.glob("*.gray"))
    if not files:
        sys.exit(f"{SRC} に .gray が無い。先にテストを回すこと")
    for path in files:
        blob = path.read_bytes()
        width = (blob[0] << 8) | blob[1]
        height = (blob[2] << 8) | blob[3]
        gray = blob[4:]
        rgb = bytearray()
        for v in gray:
            level = v >> 5              # **実機と同じ 8 段へ落としてから色にする**
            g = level * 255 // 7
            rgb += bytes((g // 6, g, g // 3))
        out = path.with_suffix(".png")
        out.write_bytes(png(width, height, bytes(rgb)))
        lit = sum(1 for v in gray if v)
        print(f"{out.relative_to(ROOT)} : {width}x{height} / 点灯 {lit} 画素 ({lit * 100 // len(gray)}%)")


if __name__ == "__main__":
    main()
