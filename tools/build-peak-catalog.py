#!/usr/bin/env python3
"""山名カタログを作る。

出典 = 国土地理院「日本の主な山岳標高（1003山）」の GeoJSON。
https://www.gsi.go.jp/kihonjohochousa/kihonjohochousa41139.html

**百名山かどうかはこのスクリプトが持つ。** 元データに百名山の別が無く、
ラベル 7 枠の優先順位（百名山を先に入れる）がこの旗で決まるため。
名前は元データと一字一句合わせる必要があるので、合わない名前があれば落とす。
"""

import argparse
import io
import json
import pathlib
import sys
import urllib.request
import zipfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "data" / "peaks.json"
CACHE = ROOT / "tools" / ".cache"

SOURCE_URL = "https://www.gsi.go.jp/KOKUJYOHO/MOUNTAIN/1003zan20260331.zip"
SOURCE_NAME = "国土地理院「日本の主な山岳標高（1003山）」"
SOURCE_PAGE = "https://www.gsi.go.jp/kihonjohochousa/kihonjohochousa41139.html"
SOURCE_LICENSE = "国土地理院コンテンツ利用規約 (https://www.gsi.go.jp/kikakuchousei/kikakuchousei40182.html)"
USER_AGENT = "minemiru-build/0.1 (+https://github.com/jigintern)"

# 深田久弥『日本百名山』。元データの「山名＜山頂名＞」と完全一致させる。
HYAKUMEIZAN = [
    "利尻山", "羅臼岳", "斜里岳", "雌阿寒岳", "大雪山", "トムラウシ山", "十勝岳", "幌尻岳", "羊蹄山",
    "岩木山", "八甲田山", "八幡平", "岩手山", "早池峰山", "鳥海山", "月山", "朝日岳", "蔵王山",
    "飯豊山", "吾妻山", "安達太良山", "磐梯山", "会津駒ヶ岳", "那須岳", "越後駒ヶ岳", "平ヶ岳",
    "巻機山", "燧ヶ岳", "至仏山", "谷川岳", "雨飾山", "苗場山", "妙高山", "火打山", "高妻山",
    "男体山", "日光白根山", "皇海山", "武尊山", "赤城山", "草津白根山", "四阿山", "浅間山",
    "筑波山", "白馬岳", "五竜岳", "鹿島槍ヶ岳", "剱岳", "立山", "薬師岳", "黒部五郎岳", "水晶岳",
    "鷲羽岳", "槍ヶ岳", "穂高岳", "常念岳", "笠ヶ岳", "焼岳", "乗鞍岳", "御嶽山", "美ヶ原",
    "霧ヶ峰", "蓼科山", "八ヶ岳", "両神山", "雲取山", "甲武信ヶ岳", "金峰山", "瑞牆山",
    "大菩薩岳", "丹沢山", "富士山", "天城山", "木曽駒ヶ岳", "空木岳", "恵那山", "甲斐駒ヶ岳",
    "仙丈ヶ岳", "鳳凰山", "北岳", "間ノ岳", "塩見岳", "荒川岳", "赤石岳", "聖岳", "光岳",
    "白山", "荒島岳", "伊吹山", "大台ヶ原山", "八経ヶ岳", "大山", "剣山", "石鎚山",
    "九重山", "祖母山", "阿蘇山", "霧島山", "開聞岳", "宮之浦岳",
]


def fetch() -> bytes:
    """GeoJSON を取る。取得済みなら使い回す（CI では走らせない前提だが手元で何度も回すため）。"""
    CACHE.mkdir(parents=True, exist_ok=True)
    cached = CACHE / "1003zan.zip"
    if not cached.exists():
        req = urllib.request.Request(SOURCE_URL, headers={"User-Agent": USER_AGENT})
        with urllib.request.urlopen(req, timeout=60) as res:
            cached.write_bytes(res.read())
    with zipfile.ZipFile(io.BytesIO(cached.read_bytes())) as z:
        name = next(n for n in z.namelist() if n.endswith(".geojson"))
        return z.read(name)


def build() -> dict:
    geo = json.loads(fetch().decode("utf-8-sig"))
    famous = set(HYAKUMEIZAN)
    seen_famous = set()

    peaks = []
    for feature in geo["features"]:
        p = feature["properties"]
        name = p["山名＜山頂名＞"].strip()
        is_famous = name in famous
        if is_famous:
            seen_famous.add(name)
        peaks.append([
            name,
            p["山名よみ＜山頂名よみ＞"].strip(),
            int(p["標高値(m)"]),
            round(float(p["緯度"]), 6),
            round(float(p["経度"]), 6),
            p["都道府県"].strip(),
            1 if is_famous else 0,
        ])

    # **合わない名前を黙って捨てない。** 捨てると百名山が 1 座だけラベルに出なくなり、
    # 実機で気づくまで分からない
    missing = sorted(famous - seen_famous)
    if missing:
        sys.exit(f"百名山の名前が元データに無い（表記を合わせること）: {missing}")
    if len(HYAKUMEIZAN) != 100:
        sys.exit(f"百名山が {len(HYAKUMEIZAN)} 座になっている")

    # 標高の高い順。ラベルが溢れたときに切る順序と揃えておくと、アプリ側で並べ直さずに済む
    peaks.sort(key=lambda r: -r[2])

    return {
        "source": f"{SOURCE_NAME} ({SOURCE_PAGE})",
        "sourceLicense": SOURCE_LICENSE,
        "sourceFile": SOURCE_URL.rsplit("/", 1)[-1],
        "generatedBy": "tools/build-peak-catalog.py",
        "note": "山名と標高と緯度経度。famous は日本百名山かどうかで、ラベルの優先枠に使う",
        "count": len(peaks),
        "famousCount": sum(r[6] for r in peaks),
        "fields": ["name", "yomi", "elevationM", "latDeg", "lonDeg", "pref", "famous"],
        "peaks": peaks,
    }


def dump(data: dict) -> str:
    """1 座を 1 行に畳む。1 要素ずつ改行すると 1000 行が 7000 行になり差分が読めない。"""
    head = {k: v for k, v in data.items() if k != "peaks"}
    body = ",\n".join("  " + json.dumps(r, ensure_ascii=False) for r in data["peaks"])
    return json.dumps(head, ensure_ascii=False, indent=1)[:-2] + ',\n "peaks": [\n' + body + "\n ]\n}\n"


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true", help="書き換えず、いまの data/ と一致するかだけ見る")
    args = ap.parse_args()

    text = dump(build())
    if args.check:
        if not OUT.exists() or OUT.read_text(encoding="utf-8") != text:
            sys.exit(f"{OUT} が生成物と一致しない。tools/build-peak-catalog.py を回すこと")
        return
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(text, encoding="utf-8")
    print(f"{OUT.relative_to(ROOT)} : {json.loads(text)['count']} 座 / 百名山 {json.loads(text)['famousCount']} 座")


if __name__ == "__main__":
    main()
