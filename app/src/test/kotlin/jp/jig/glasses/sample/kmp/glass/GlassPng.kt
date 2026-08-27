package jp.jig.glasses.sample.kmp.glass

import java.io.File
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * グラスの階調画像を PNG に書き出す。**テストで目で見るためだけのもの。**
 *
 * `java.awt` は Android のユニットテストのクラスパスに無い（android.jar のスタブが
 * 前に出る）ので、PNG を自分で組む。使うのは JDK の Deflater と CRC32 だけ。
 *
 * **グラスに出るのと同じ 8 段に落としてから緑にする。** 中間階調が屋外で見えるかを
 * 判断したいので、勝手に持ち上げない。
 */
object GlassPng {

    fun save(gray: ByteArray, width: Int, height: Int, dir: String, name: String): File {
        val out = File(dir).apply { mkdirs() }
        val file = File(out, "$name.png")

        // 走査線ごとにフィルタ 0（無変換）＋ RGB
        val raw = ByteArray(height * (1 + width * 3))
        var p = 0
        for (y in 0 until height) {
            raw[p++] = 0
            for (x in 0 until width) {
                val level = (gray[y * width + x].toInt() and 0xFF) ushr 5
                val g = level * 255 / 7
                raw[p++] = (g / 6).toByte()   // R を少し入れて、黒背景でも緑が沈まないように
                raw[p++] = g.toByte()
                raw[p++] = (g / 6).toByte()
            }
        }

        val body = ArrayList<Byte>()
        fun chunk(type: String, data: ByteArray) {
            body += be32(data.size).toList()
            val typed = type.toByteArray(Charsets.US_ASCII) + data
            body += typed.toList()
            val crc = CRC32().apply { update(typed) }.value.toInt()
            body += be32(crc).toList()
        }

        chunk("IHDR", be32(width) + be32(height) + byteArrayOf(8, 2, 0, 0, 0))
        chunk("IDAT", deflate(raw))
        chunk("IEND", ByteArray(0))

        file.writeBytes(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10) + body.toByteArray())
        return file
    }

    private fun be32(v: Int) = byteArrayOf(
        (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte(),
    )

    private fun deflate(data: ByteArray): ByteArray {
        val d = Deflater(Deflater.BEST_COMPRESSION)
        d.setInput(data)
        d.finish()
        val buf = ByteArray(data.size + 1024)
        val n = d.deflate(buf)
        d.end()
        return buf.copyOf(n)
    }
}
