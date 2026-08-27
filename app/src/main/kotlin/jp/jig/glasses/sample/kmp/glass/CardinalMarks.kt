package jp.jig.glasses.sample.kmp.glass

import jp.jig.glasses.sample.kmp.geo.Basis
import jp.jig.glasses.sample.kmp.geo.enu
import jp.jig.glasses.sample.kmp.geo.project
import kotlin.math.roundToInt

/**
 * 方位（N/E/S/W）の目印。**方位合わせが合っているかを目で確かめられる唯一の手段。**
 *
 * 稜線だけを出していると、ずれていても「そういう形の山なのだろう」と見えてしまう。
 * N が北を指していなければ一目で分かる。段階6 で画角と方位を追い込むときの物差しでもある。
 *
 * ## 文字を画像に焼く理由
 *
 * キャンバスのテキスト枠は **8 枠・190 バイトしかなく、山名で使い切る**
 * （[PeakLabels.SLOTS] が 7、残り 1 枠は案内用）。N/E/S/W に 4 枠使うと山名が出ない。
 * 線で書けば枠を 1 つも食わない。
 *
 * ## 階調を一段落とす理由
 *
 * 稜線は実景の山に**重ねる**線なので最上段（[Ink.SKYLINE]）でなければ負ける。
 * 方位は空の何もない所に出る位置の手がかりなので、[Ink.CARDINAL] で足りる
 * ——**稜線と同じ明るさにすると、どちらが山の形なのか分からなくなる**。
 *
 * **屋外の昼に階調 4 が見えるかは未実測。** 見えなければ [Ink.SKYLINE] へ上げるのではなく、
 * まず線幅（[MARK_RADIUS]）を上げること。同じ明るさにすると上の理由で読めなくなる。
 */
object CardinalMarks {

    /** 目印を出す方位と字。8 方位にすると視野 35° に 2 つ入って字が重なる */
    private val CARDINALS = listOf(
        0.0 to 'N',
        90.0 to 'E',
        180.0 to 'S',
        270.0 to 'W',
    )

    /** 線の太さ。稜線より細い（[RidgeRenderer.SKYLINE_EXTRA_RADIUS] を足さない） */
    const val MARK_RADIUS = 1

    /** 字の大きさ[px]。528×330 で読める下限あたり。**未実測** */
    const val GLYPH_WIDTH = 16
    const val GLYPH_HEIGHT = 22

    /** 画像の下辺から字の下までの余白[px]。**稜線は上半分に出るので下に置く** */
    const val BOTTOM_MARGIN = 8

    /** 字から上へ伸ばす爪の長さ[px]。どの位置を指しているかを示す */
    const val TICK_LENGTH = 12

    /**
     * 方位の目印を焼き込む。**視野に入っている方位だけ。**
     *
     * 縦の位置は画像の下辺に固定する。地平線（仰角 0°）に置くと稜線と重なって
     * どちらも読めなくなるし、見上げたときに画面から消えてしまう。
     * **横の位置だけが意味を持つ**ので、投影した x をそのまま使う。
     */
    fun ByteArray.cardinalMarks(width: Int, height: Int, basis: Basis, k: Double) {
        val glyphBottom = height - BOTTOM_MARGIN
        val glyphTop = glyphBottom - GLYPH_HEIGHT
        for ((azimuthDeg, letter) in CARDINALS) {
            // 仰角 0° の方向を投影して横位置を取る。縦は捨てる
            val point = project(enu(azimuthDeg, 0.0), basis, k, width, height) ?: continue
            val x = point[0].roundToInt()
            // 字が画面に収まらない位置なら出さない（半端に切れた字は読めない）
            if (x - GLYPH_WIDTH / 2 < 0 || x + GLYPH_WIDTH / 2 >= width) continue
            line(width, height, x, glyphTop - TICK_LENGTH, x, glyphTop - 2, Ink.CARDINAL, MARK_RADIUS)
            glyph(width, height, letter, x - GLYPH_WIDTH / 2, glyphTop, GLYPH_WIDTH, GLYPH_HEIGHT)
        }
    }

    /**
     * 字を線で書く。**フォントを持たない**（グラスは画像しか受け取らないので、
     * 文字を焼くなら自分で書くしかない）。4 文字ぶんだけなので直に置く。
     */
    private fun ByteArray.glyph(width: Int, height: Int, letter: Char, x: Int, y: Int, w: Int, h: Int) {
        val right = x + w
        val bottom = y + h
        val middle = y + h / 2
        fun seg(x0: Int, y0: Int, x1: Int, y1: Int) =
            line(width, height, x0, y0, x1, y1, Ink.CARDINAL, MARK_RADIUS)
        when (letter) {
            'N' -> {
                seg(x, bottom, x, y)
                seg(x, y, right, bottom)
                seg(right, bottom, right, y)
            }
            'E' -> {
                seg(x, y, x, bottom)
                seg(x, y, right, y)
                seg(x, middle, right, middle)
                seg(x, bottom, right, bottom)
            }
            'S' -> {
                seg(right, y, x, y)
                seg(x, y, x, middle)
                seg(x, middle, right, middle)
                seg(right, middle, right, bottom)
                seg(right, bottom, x, bottom)
            }
            'W' -> {
                seg(x, y, x + w / 4, bottom)
                seg(x + w / 4, bottom, x + w / 2, middle)
                seg(x + w / 2, middle, right - w / 4, bottom)
                seg(right - w / 4, bottom, right, y)
            }
        }
    }
}
