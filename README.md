# 峰ミル

SABERA スマートグラスに**山の稜線と山名**を重ねる Android アプリ。
平地から遠景を見る想定（鯖江から白山、甲府から富士山）。

星座版の姉妹アプリ **[星しるべ](https://github.com/jigintern/sabera-team-e)** から
座標変換・グラス出力・姿勢補正の資産を借りている。

## 使い方（開発）

```bash
./gradlew :app:installDebug        # 実機にインストール（BLE 必須。エミュレータ不可）
./gradlew :app:testDebugUnitTest   # JVM テスト
```

同梱データの作り直し：

```bash
python3 tools/build-peak-catalog.py   # data/peaks.json（国土地理院 1003山）
python3 tools/build-dem.py            # data/dem/（全国 z10・約18MB・外部取得あり）
```

## ドキュメント

| 知りたいこと | 読む先 |
|---|---|
| **いまどこまで動いているか** | [docs/STATUS.md](docs/STATUS.md) |
| 何を作るか・なぜそう決めたか | [docs/PLAN.md](docs/PLAN.md) |
| 言葉の定義 | [CONTEXT.md](CONTEXT.md) |
| エージェント向けの規約 | [AGENTS.md](AGENTS.md) |
| 地形まわりの実測値 | [docs/team-e/76_terrain-measurements.md](docs/team-e/76_terrain-measurements.md) |

## 出典

| 資産 | 出典 |
|---|---|
| `data/peaks.json` | 国土地理院「日本の主な山岳標高（1003山）」 |
| `data/dem/` | [地理院タイル（標高タイル）](https://maps.gsi.go.jp/development/demtile.html)。int16 化・4m 量子化・再圧縮の加工を加えている |

いずれも[国土地理院コンテンツ利用規約](https://www.gsi.go.jp/kikakuchousei/kikakuchousei40182.html)に基づく。
