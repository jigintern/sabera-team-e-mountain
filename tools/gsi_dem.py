"""地理院標高タイルの取得と復号。build-dem.py と検証スクリプトで共有する。

**PNG を使うのは配信元への負荷を減らすため。** 同じタイルが PNG 130KB / txt 430KB で、
581 枚取ると 3 倍違う。PNG の復号は自前（PIL を足したくない）。
"""

import pathlib
import struct
import time
import urllib.error
import urllib.request
import zlib

USER_AGENT = "minemiru-build/0.1 (+https://github.com/jigintern)"
BASE_PNG = "https://cyberjapandata.gsi.go.jp/xyz/dem_png"
BASE_TXT = "https://cyberjapandata.gsi.go.jp/xyz/dem"
TILE = 256
NODATA = -32768

# 連続取得の間隔。**配信元は申請不要のリアルタイム利用を想定した口**なので、
# まとめ取りするときは自分で間隔を空ける
POLITE_SLEEP_S = 0.15


class TileMissing(Exception):
    """海など、そもそもタイルが存在しない（404）。"""


def _get(url: str, timeout: int = 60) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as res:
            return res.read()
    except urllib.error.HTTPError as e:
        if e.code == 404:
            raise TileMissing(url) from e
        raise


def decode_png_gray(raw: bytes) -> list:
    """PNG を画素の R,G,B 3 つ組の平坦な配列に開く。8bit の truecolor だけ相手にする。"""
    if raw[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("PNG ではない")
    pos, idat, width, height, bpp = 8, bytearray(), 0, 0, 0
    while pos < len(raw):
        (length,) = struct.unpack(">I", raw[pos:pos + 4])
        kind = raw[pos + 4:pos + 8]
        body = raw[pos + 8:pos + 8 + length]
        if kind == b"IHDR":
            width, height, depth, color = struct.unpack(">IIBB", body[:10])
            if depth != 8 or color not in (2, 6):
                raise ValueError(f"未対応の PNG: depth={depth} color={color}")
            bpp = 3 if color == 2 else 4
        elif kind == b"IDAT":
            idat += body
        elif kind == b"IEND":
            break
        pos += 12 + length

    data = zlib.decompress(bytes(idat))
    stride = width * bpp
    out = bytearray(stride * height)
    prev = bytearray(stride)
    src = 0
    for y in range(height):
        filt = data[src]
        src += 1
        row = bytearray(data[src:src + stride])
        src += stride
        if filt == 1:  # Sub
            for i in range(bpp, stride):
                row[i] = (row[i] + row[i - bpp]) & 0xFF
        elif filt == 2:  # Up
            for i in range(stride):
                row[i] = (row[i] + prev[i]) & 0xFF
        elif filt == 3:  # Average
            for i in range(stride):
                left = row[i - bpp] if i >= bpp else 0
                row[i] = (row[i] + ((left + prev[i]) >> 1)) & 0xFF
        elif filt == 4:  # Paeth
            for i in range(stride):
                a = row[i - bpp] if i >= bpp else 0
                b = prev[i]
                c = prev[i - bpp] if i >= bpp else 0
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                row[i] = (row[i] + pr) & 0xFF
        elif filt != 0:
            raise ValueError(f"未知のフィルタ {filt}")
        out[y * stride:(y + 1) * stride] = row
        prev = row
    return out, width, height, bpp


def elevations_from_png(raw: bytes) -> list:
    """標高[m]の配列（256×256、無効値は None）。仕様どおり符号付き 24bit として読む。"""
    px, w, h, bpp = decode_png_gray(raw)
    out = []
    for i in range(w * h):
        r, g, b = px[i * bpp], px[i * bpp + 1], px[i * bpp + 2]
        x = (r << 16) | (g << 8) | b
        if x == 0x800000:
            out.append(None)
        else:
            out.append((x - 0x1000000 if x > 0x800000 else x) * 0.01)
    return out


def elevations_from_txt(raw: bytes) -> list:
    out = []
    for line in raw.decode().splitlines():
        for tok in line.strip().split(","):
            if tok:
                out.append(None if tok == "e" else float(tok))
    return out


def fetch_png(z: int, x: int, y: int, cache: pathlib.Path = None) -> bytes:
    if cache is not None:
        p = cache / f"{z}_{x}_{y}.png"
        if p.exists():
            return p.read_bytes()
    raw = _get(f"{BASE_PNG}/{z}/{x}/{y}.png")
    time.sleep(POLITE_SLEEP_S)
    if cache is not None:
        cache.mkdir(parents=True, exist_ok=True)
        (cache / f"{z}_{x}_{y}.png").write_bytes(raw)
    return raw


def fetch_txt(z: int, x: int, y: int) -> bytes:
    raw = _get(f"{BASE_TXT}/{z}/{x}/{y}.txt")
    time.sleep(POLITE_SLEEP_S)
    return raw
