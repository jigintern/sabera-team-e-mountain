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

# 深田久弥『日本百名山』100 座。**(山名＜山頂名＞, 標高値, 深田の呼び名)** で持つ。
#
# **名前だけでは指せない。**「駒ヶ岳」は元データに 6 座あり、うち 4 座が百名山
# （会津・越後・木曽・甲斐）。「白根山」は 2 座あって両方とも百名山。
# 名前で照合すると秋田駒ヶ岳(1158m)まで百名山になる。
# (名前, 標高) の組は元データ 1059 件で衝突が無いことを確認済み。
#
# **3 列目の深田の呼び名はラベルに出す。** 元データの「赤岳」より「八ヶ岳」、
# 「白山＜御前峰＞」より「白山」のほうが伝わる。使う人は登山の初心者。
HYAKUMEIZAN = [
    ("利尻山（利尻富士）", 1721, "利尻山"),
    ("羅臼岳", 1660, "羅臼岳"),
    ("斜里岳", 1547, "斜里岳"),
    ("雌阿寒岳", 1499, "阿寒岳"),
    ("大雪山（ヌタプカウシペ）＜旭岳＞", 2291, "大雪山"),
    ("トムラウシ山", 2141, "トムラウシ山"),
    ("十勝岳", 2077, "十勝岳"),
    ("幌尻岳", 2052, "幌尻岳"),
    ("羊蹄山（蝦夷富士）", 1898, "羊蹄山"),
    ("岩木山", 1624, "岩木山"),
    ("八甲田山＜大岳＞", 1584, "八甲田山"),
    ("八幡平", 1613, "八幡平"),
    ("岩手山", 2038, "岩手山"),
    ("早池峰山", 1917, "早池峰山"),
    ("鳥海山＜新山＞", 2236, "鳥海山"),
    ("月山", 1984, "月山"),
    ("朝日岳＜大朝日岳＞", 1870, "朝日岳"),
    ("蔵王山＜熊野岳＞", 1841, "蔵王山"),
    ("飯豊山", 2105, "飯豊山"),
    ("西吾妻山", 2035, "吾妻山"),
    ("安達太良山", 1700, "安達太良山"),
    ("磐梯山", 1816, "磐梯山"),
    ("駒ヶ岳", 2133, "会津駒ヶ岳"),
    ("那須岳＜茶臼岳＞", 1915, "那須岳"),
    ("駒ヶ岳", 2003, "越後駒ヶ岳"),
    ("平ヶ岳", 2141, "平ヶ岳"),
    ("巻機山", 1967, "巻機山"),
    ("燧ヶ岳＜柴安嵓＞", 2356, "燧ヶ岳"),
    ("至仏山", 2228, "至仏山"),
    ("谷川岳＜オキノ耳＞", 1977, "谷川岳"),
    ("雨飾山", 1963, "雨飾山"),
    ("苗場山", 2145, "苗場山"),
    ("妙高山", 2454, "妙高山"),
    ("火打山", 2462, "火打山"),
    ("高妻山", 2353, "高妻山"),
    ("男体山", 2486, "男体山"),
    ("白根山", 2578, "日光白根山"),
    ("皇海山", 2144, "皇海山"),
    ("武尊山", 2158, "武尊山"),
    ("赤城山＜黒檜山＞", 1828, "赤城山"),
    ("白根山", 2160, "草津白根山"),
    ("四阿山", 2354, "四阿山"),
    ("浅間山", 2568, "浅間山"),
    ("筑波山", 877, "筑波山"),
    ("白馬岳", 2932, "白馬岳"),
    ("五龍岳", 2814, "五竜岳"),
    ("鹿島槍ヶ岳", 2889, "鹿島槍ヶ岳"),
    ("剱岳", 2999, "剱岳"),
    ("立山＜大汝山＞", 3015, "立山"),
    ("薬師岳", 2926, "薬師岳"),
    ("黒部五郎岳（中ノ俣岳）", 2840, "黒部五郎岳"),
    ("水晶岳（黒岳）", 2986, "水晶岳"),
    ("鷲羽岳", 2924, "鷲羽岳"),
    ("槍ヶ岳", 3180, "槍ヶ岳"),
    ("奥穂高岳", 3190, "穂高岳"),
    ("常念岳", 2857, "常念岳"),
    ("笠ヶ岳", 2897, "笠ヶ岳"),
    ("焼岳", 2455, "焼岳"),
    ("乗鞍岳＜剣ヶ峰＞", 3026, "乗鞍岳"),
    ("御嶽山＜剣ヶ峰＞", 3067, "御嶽山"),
    ("美ヶ原＜王ヶ頭＞", 2034, "美ヶ原"),
    ("霧ヶ峰＜車山＞", 1925, "霧ヶ峰"),
    ("蓼科山", 2531, "蓼科山"),
    ("赤岳", 2899, "八ヶ岳"),
    ("両神山", 1723, "両神山"),
    ("雲取山", 2017, "雲取山"),
    ("甲武信ヶ岳", 2475, "甲武信ヶ岳"),
    ("金峰山", 2599, "金峰山"),
    ("瑞牆山", 2230, "瑞牆山"),
    ("大菩薩嶺", 2057, "大菩薩嶺"),
    ("丹沢山", 1567, "丹沢山"),
    ("富士山＜剣ヶ峯＞", 3776, "富士山"),
    ("天城山＜万三郎岳＞", 1405, "天城山"),
    ("駒ヶ岳", 2956, "木曽駒ヶ岳"),
    ("空木岳", 2864, "空木岳"),
    ("恵那山", 2191, "恵那山"),
    ("駒ヶ岳", 2967, "甲斐駒ヶ岳"),
    ("仙丈ヶ岳", 3033, "仙丈ヶ岳"),
    ("薬師ヶ岳", 2780, "鳳凰山"),
    ("北岳", 3193, "北岳"),
    ("間ノ岳", 3190, "間ノ岳"),
    ("塩見岳", 3052, "塩見岳"),
    ("東岳（悪沢岳）", 3141, "悪沢岳"),
    ("赤石岳", 3121, "赤石岳"),
    ("聖岳＜前聖岳＞", 3013, "聖岳"),
    ("光岳", 2592, "光岳"),
    ("白山＜御前峰＞", 2702, "白山"),
    ("荒島岳", 1523, "荒島岳"),
    ("伊吹山", 1377, "伊吹山"),
    ("大台ヶ原山＜日出ヶ岳＞", 1695, "大台ヶ原山"),
    ("八経ヶ岳", 1915, "大峰山"),
    ("剣山", 1955, "剣山"),
    ("石鎚山＜天狗岳＞", 1982, "石鎚山"),
    ("くじゅう連山＜久住山＞", 1786, "九重山"),
    ("祖母山", 1756, "祖母山"),
    ("阿蘇山＜高岳＞", 1592, "阿蘇山"),
    ("霧島山＜韓国岳＞", 1700, "霧島山"),
    ("開聞岳", 924, "開聞岳"),
    ("宮之浦岳", 1936, "宮之浦岳"),
    ("大山＜剣ヶ峰＞", 1729, "大山"),
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
    famous = {(n, e): f for n, e, f in HYAKUMEIZAN}
    seen_famous = set()

    peaks = []
    for feature in geo["features"]:
        p = feature["properties"]
        name = p["山名＜山頂名＞"].strip()
        elevation = int(p["標高値(m)"])
        key = (name, elevation)
        famous_name = famous.get(key)
        if famous_name:
            seen_famous.add(key)
        peaks.append([
            name,
            p["山名よみ＜山頂名よみ＞"].strip(),
            elevation,
            round(float(p["緯度"]), 6),
            round(float(p["経度"]), 6),
            p["都道府県"].strip(),
            famous_name or "",
        ])

    # **合わない名前を黙って捨てない。** 捨てると百名山が 1 座だけラベルに出なくなり、
    # 実機で気づくまで分からない
    missing = sorted(set(famous) - seen_famous)
    if missing:
        sys.exit(f"百名山が元データに無い（山名と標高を合わせること）: {missing}")
    if len(HYAKUMEIZAN) != 100:
        sys.exit(f"百名山が {len(HYAKUMEIZAN)} 座になっている")

    # 標高の高い順。ラベルが溢れたときに切る順序と揃えておくと、アプリ側で並べ直さずに済む
    peaks.sort(key=lambda r: -r[2])

    return {
        "source": f"{SOURCE_NAME} ({SOURCE_PAGE})",
        "sourceLicense": SOURCE_LICENSE,
        "sourceFile": SOURCE_URL.rsplit("/", 1)[-1],
        "generatedBy": "tools/build-peak-catalog.py",
        "note": "山名と標高と緯度経度。famousName は日本百名山の通称で、空なら百名山ではない。ラベルの優先枠に使う",
        "count": len(peaks),
        "famousCount": sum(1 for r in peaks if r[6]),
        "fields": ["name", "yomi", "elevationM", "latDeg", "lonDeg", "pref", "famousName"],
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
