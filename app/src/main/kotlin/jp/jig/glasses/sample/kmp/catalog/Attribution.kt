package jp.jig.glasses.sample.kmp.catalog

/**
 * 同梱データの出典。**アプリの中に出す義務がある側。**
 *
 * `data/peaks.json` と `data/dem/` はどちらも国土地理院のコンテンツで、
 * [国土地理院コンテンツ利用規約](https://www.gsi.go.jp/kikakuchousei/kikakuchousei40182.html)
 * は商用も加工も許すかわりに **出典の明示** と、加工したなら **加工した旨の明示** を求める。
 * 峰ミルは標高タイルを int16 化・4m 量子化・再圧縮しているので、**両方に当たる**。
 *
 * **ここが唯一の出どころ。** README と NOTICE は人が読む用の写しで、
 * 画面に出す文字はこの定数から取る —— 同じ文言を 2 か所で手直しすると必ず片方が古くなる。
 */
object Attribution {

    /** 山名カタログ（1003 山）の出どころ。加工していない */
    const val PEAKS = "国土地理院「日本の主な山岳標高」"

    /** 標高タイルの出どころ。**加工した旨まで書く** */
    const val ELEVATION = "国土地理院「地理院タイル（標高タイル）」を加工"

    /** 画面 1 行に収める形。ホームの足元に出す */
    const val ONE_LINE = "出典: $PEAKS / $ELEVATION"

    /** 規約の在り処。聞かれたときに示せるようにしておく */
    const val TERMS_URL = "https://www.gsi.go.jp/kikakuchousei/kikakuchousei40182.html"
}
