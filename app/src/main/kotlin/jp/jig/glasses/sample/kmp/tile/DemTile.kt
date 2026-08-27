package jp.jig.glasses.sample.kmp.tile

import java.util.zip.Inflater
import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/**
 * 同梱の標高タイル 1 枚。**Android に触らない**ので JVM テストで数字を固定できる。
 *
 * 形式は `tools/build-dem.py` が書くもの:
 *
 * ```
 * "MDM1" | 量子化[1] | log2(辺)[1] | zlib( int16 の行内差分 × 辺² )
 * ```
 *
 * **無効値は 0m** として入っている。行差分にセンチネル(-32768)を混ぜると
 * 差分が int16 を溢れて壊れるため。無効値はほぼ海なので 0m は意味としても正しく、
 * 内陸の欠測も**遮蔽判定では「隠さない」側に倒れる**ので安全側。
 */
class DemTile(val size: Int, val quantM: Int, private val values: ShortArray) {

    /** タイル内の画素 ([col], [row]) の標高[m]。範囲外は端に丸める。 */
    fun elevationM(col: Int, row: Int): Double {
        val c = col.coerceIn(0, size - 1)
        val r = row.coerceIn(0, size - 1)
        return values[r * size + c] * quantM.toDouble()
    }

    companion object {
        private val MAGIC = byteArrayOf('M'.code.toByte(), 'D'.code.toByte(), 'M'.code.toByte(), '1'.code.toByte())

        /** [encode][tools/build-dem.py] の逆。形式がずれたらここで例外になる。 */
        fun decode(raw: ByteArray): DemTile {
            require(raw.size > 6 && raw.copyOfRange(0, 4).contentEquals(MAGIC)) { "標高タイルの形式が違う" }
            val quant = raw[4].toInt() and 0xFF
            val size = 1 shl (raw[5].toInt() and 0xFF)
            val count = size * size

            val inflater = Inflater()
            inflater.setInput(raw, 6, raw.size - 6)
            val bytes = ByteArray(count * 2)
            var written = 0
            while (written < bytes.size) {
                val n = inflater.inflate(bytes, written, bytes.size - written)
                if (n == 0) break
                written += n
            }
            inflater.end()
            require(written == bytes.size) { "標高タイルが途中で終わっている（$written / ${bytes.size}）" }

            // 行ごとに、直前の画素との差分を積む（little endian の int16）
            val values = ShortArray(count)
            var i = 0
            for (row in 0 until size) {
                var prev = 0
                for (col in 0 until size) {
                    val lo = bytes[i].toInt() and 0xFF
                    val hi = bytes[i + 1].toInt()
                    i += 2
                    prev += (hi shl 8) or lo
                    values[row * size + col] = prev.toShort()
                }
            }
            return DemTile(size, quant, values)
        }
    }
}

/** Web メルカトルのタイル座標。**地理院タイルも同じ並び。** */
object TileGrid {

    /** 経度 → タイル X（小数部が画素の位置） */
    fun xOf(lonDeg: Double, zoom: Int): Double = (lonDeg + 180.0) / 360.0 * (1 shl zoom)

    /** 緯度 → タイル Y（小数部が画素の位置） */
    fun yOf(latDeg: Double, zoom: Int): Double {
        val r = Math.toRadians(latDeg)
        return (1.0 - ln(tan(r) + 1.0 / kotlin.math.cos(r)) / PI) / 2.0 * (1 shl zoom)
    }

    /** タイル X → その左端の経度 */
    fun lonOf(x: Double, zoom: Int): Double = x / (1 shl zoom) * 360.0 - 180.0

    /** タイル Y → その上端の緯度 */
    fun latOf(y: Double, zoom: Int): Double =
        Math.toDegrees(kotlin.math.atan(kotlin.math.sinh(PI * (1.0 - 2.0 * y / (1 shl zoom)))))

    /** 小数を含むタイル座標から、タイル番号と画素の位置に割る。 */
    fun floorInt(v: Double): Int = floor(v).toInt()

    @Suppress("unused")
    private fun keepAsinh() = asinh(0.0)
}
