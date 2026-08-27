#!/usr/bin/env python3
"""同梱する標高データを作る。全国 z10（画素 124m）。

出典 = 地理院タイル（標高タイル）https://maps.gsi.go.jp/development/demtile.html

**CI では回さない。** 外部取得が必要なので、星しるべの build-satellites.py と同じ扱い
（生成物はコミットするが再生成検査からは外す）。

なぜ自前形式か
  配信の PNG は 1cm 分解能で、下位ビットはこの用途では純粋なノイズ。
  int16[4m 量子化] + 行差分 + zlib にすると 130KB → 約 32KB になる。

なぜ無効値を 0m にするか
  行差分にセンチネル(-32768)を混ぜると差分が int16 を溢れて壊れる。
  そして無効値はほぼ海なので、0m は意味としても正しい。内陸の欠測も
  0m になるが、**遮蔽判定では「隠さない」側に倒れる**ので安全側。
"""

import argparse
import json
import math
import pathlib
import struct
import sys
import zlib

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from gsi_dem import TileMissing, elevations_from_png, fetch_png  # noqa: E402

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "data" / "dem"
CACHE = ROOT / "tools" / ".cache" / "dem"

ZOOM = 10
PROBE_ZOOM = 8       # ここで 404 なら配下 16 枚は海。**先に刈らないと無駄な要求を配信元へ投げる**
QUANT_M = 4          # 30km 先で 0.15 画素。中距離用に十分
TILE = 256
MAGIC = b"MDM1"

# 1003 山カタログの外接矩形に少し余裕を持たせた範囲（与那国から知床まで）
LAT0, LAT1 = 23.7, 45.9
LON0, LON1 = 122.5, 146.0


def tile_range(z: int, lat0: float, lat1: float, lon0: float, lon1: float):
    n = 2 ** z

    def X(lon):
        return int((lon + 180) / 360 * n)

    def Y(lat):
        r = math.radians(lat)
        return int((1 - math.log(math.tan(r) + 1 / math.cos(r)) / math.pi) / 2 * n)

    return range(X(lon0), X(lon1) + 1), range(Y(lat1), Y(lat0) + 1)


def encode(elevations: list) -> bytes:
    """量子化 → 行ごとの差分 → zlib。"""
    q = [0 if v is None else int(round(v / QUANT_M)) for v in elevations]
    deltas = []
    for row in range(TILE):
        prev = 0
        for v in q[row * TILE:(row + 1) * TILE]:
            d = v - prev
            if not -32768 <= d <= 32767:
                raise ValueError(f"差分が int16 を超えた: {d}")
            deltas.append(d)
            prev = v
    body = zlib.compress(struct.pack(f"<{len(deltas)}h", *deltas), 9)
    return MAGIC + bytes([QUANT_M, 8]) + body


def decode(raw: bytes) -> list:
    """[encode] の逆。アプリ側の実装と突き合わせるための参照実装。"""
    if raw[:4] != MAGIC:
        raise ValueError("形式が違う")
    quant, log_size = raw[4], raw[5]
    size = 1 << log_size
    deltas = struct.unpack(f"<{size * size}h", zlib.decompress(raw[6:]))
    out = []
    for row in range(size):
        prev = 0
        for d in deltas[row * size:(row + 1) * size]:
            prev += d
            out.append(prev * quant)
    return out


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--verify", action="store_true", help="書いたファイルが読み戻せるかだけ見る")
    args = ap.parse_args()

    if args.verify:
        index = json.loads((OUT / "index.json").read_text(encoding="utf-8"))
        bad = 0
        for key in index["tiles"]:
            x, y = (int(v) for v in key.split("/"))
            values = decode((OUT / str(ZOOM) / str(x) / f"{y}.bin").read_bytes())
            if len(values) != TILE * TILE or max(values) > 4000 or min(values) < -100:
                print(f"  ✗ {key}: 件数 {len(values)} 範囲 {min(values)}〜{max(values)}")
                bad += 1
        print(f"検証: {len(index['tiles'])} 枚 / 異常 {bad} 枚")
        sys.exit(1 if bad else 0)

    CACHE.mkdir(parents=True, exist_ok=True)
    xs8, ys8 = tile_range(PROBE_ZOOM, LAT0, LAT1, LON0, LON1)
    probes = [(x, y) for x in xs8 for y in ys8]
    print(f"z{PROBE_ZOOM} で刈り込む: {len(probes)} 枚を確認", flush=True)

    live8 = []
    for i, (x, y) in enumerate(probes):
        try:
            fetch_png(PROBE_ZOOM, x, y, CACHE)
            live8.append((x, y))
        except TileMissing:
            pass
        if (i + 1) % 50 == 0:
            print(f"  {i + 1}/{len(probes)} 確認 / 陸 {len(live8)}", flush=True)
    step = 2 ** (ZOOM - PROBE_ZOOM)
    print(f"z{PROBE_ZOOM} で陸だったのは {len(live8)} 枚 → z{ZOOM} の候補 {len(live8) * step * step} 枚", flush=True)

    tiles, total = [], 0
    todo = [(x8 * step + dx, y8 * step + dy) for x8, y8 in live8 for dx in range(step) for dy in range(step)]
    for i, (x, y) in enumerate(todo):
        path = OUT / str(ZOOM) / str(x) / f"{y}.bin"
        if path.exists():
            tiles.append(f"{x}/{y}")
            total += path.stat().st_size
            continue
        try:
            raw = fetch_png(ZOOM, x, y, CACHE)
        except TileMissing:
            continue
        blob = encode(elevations_from_png(raw))
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(blob)
        tiles.append(f"{x}/{y}")
        total += len(blob)
        if len(tiles) % 50 == 0:
            print(f"  {i + 1}/{len(todo)} 走査 / 保存 {len(tiles)} 枚 / {total / 1048576:.1f} MB", flush=True)

    tiles.sort()
    (OUT / "index.json").write_text(json.dumps({
        "source": "地理院タイル（標高タイル） https://maps.gsi.go.jp/development/demtile.html",
        "sourceLicense": "国土地理院コンテンツ利用規約。int16 化・4m 量子化・再圧縮の加工を加えている",
        "generatedBy": "tools/build-dem.py",
        "zoom": ZOOM,
        "tileSize": TILE,
        "quantMeters": QUANT_M,
        "nodata": "無効値は 0m として格納する（ほぼ海。遮蔽判定では隠さない側に倒れる）",
        "count": len(tiles),
        "tiles": tiles,
    }, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"\ndata/dem : {len(tiles)} 枚 / {total / 1048576:.1f} MB", flush=True)


if __name__ == "__main__":
    main()
