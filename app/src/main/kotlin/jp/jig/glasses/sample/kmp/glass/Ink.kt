package jp.jig.glasses.sample.kmp.glass

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 3bit の階調。**黒（0）は透明**で、実景がそのまま見える。
 *
 * 屋外では暗い画素は空に負ける。星しるべの実測では 2〜4px の点は背後の星と
 * 見分けが付かなかった。**稜線は最上段だけを使い、線幅で存在感を稼ぐ。**
 */
object Ink {
    /** 空と大地の境界。実景の山と重ねるので、いちばん明るくないと見えない */
    const val SKYLINE = 255

    /** 名前のある峰どうしを繋ぐ線。稜線より一段落とす */
    const val RIDGE_LINE = 5 * 255 / 7

    /** 方位（N/E/S/W）。位置の手がかりなので控えめでよい */
    const val CARDINAL = 4 * 255 / 7

    /** 何も描かない = 透明 */
    const val CLEAR = 0
}

/**
 * 線幅を画像の幅から決める。**528px で 3px は実機で見えなかった**ので、
 * 星しるべが辿り着いた `width / 264` を踏襲する（528px → 2px が下限）。
 */
fun lineRadius(width: Int): Int = max(1, width / 264)

/** 1 画素を置く。範囲外は黙って捨てる（画面の外へ出る線を毎回切らずに済む）。 */
fun ByteArray.plot(width: Int, height: Int, x: Int, y: Int, value: Int) {
    if (x < 0 || y < 0 || x >= width || y >= height) return
    this[y * width + x] = value.toByte()
}

/**
 * 太い点。[radius] は半径で、`round` なら丸く落とす。
 *
 * 四角のままだと明るい点が 17×17 の塊になって稜線が団子になる（星しるべの実測）。
 */
fun ByteArray.dot(width: Int, height: Int, x: Int, y: Int, value: Int, radius: Int, round: Boolean = true) {
    if (radius <= 0) {
        plot(width, height, x, y, value)
        return
    }
    val r2 = radius * radius
    for (dy in -radius..radius) {
        for (dx in -radius..radius) {
            if (round && dx * dx + dy * dy > r2) continue
            plot(width, height, x + dx, y + dy, value)
        }
    }
}

/**
 * 太い線。Bresenham で置いて、各点を [radius] の丸で太らせる。
 *
 * [dash] が正なら、その画素数ごとに描いて空ける。
 */
fun ByteArray.line(
    width: Int,
    height: Int,
    x0: Int,
    y0: Int,
    x1: Int,
    y1: Int,
    value: Int,
    radius: Int = 0,
    dash: Int = 0,
) {
    var x = x0
    var y = y0
    val dx = abs(x1 - x0)
    val dy = -abs(y1 - y0)
    val sx = if (x0 < x1) 1 else -1
    val sy = if (y0 < y1) 1 else -1
    var err = dx + dy
    var step = 0
    while (true) {
        if (dash <= 0 || (step / dash) % 2 == 0) dot(width, height, x, y, value, radius)
        if (x == x1 && y == y1) break
        val e2 = 2 * err
        if (e2 >= dy) {
            err += dy
            x += sx
        }
        if (e2 <= dx) {
            err += dx
            y += sy
        }
        step++
    }
}

/**
 * 方位や仰角の折れ線を、画面座標の列として描く。
 *
 * **稜線はこれで描く。** 点の列を渡すと、隣どうしを繋いだ 1 本の太い線になる。
 * 画面外へ出た区間は [line] が画素単位で捨てるので、呼ぶ側で切らなくてよい。
 */
fun ByteArray.polyline(
    width: Int,
    height: Int,
    points: List<Pair<Double, Double>>,
    value: Int,
    radius: Int,
) {
    for (i in 0 until points.size - 1) {
        val (ax, ay) = points[i]
        val (bx, by) = points[i + 1]
        line(width, height, ax.roundToInt(), ay.roundToInt(), bx.roundToInt(), by.roundToInt(), value, radius)
    }
}
