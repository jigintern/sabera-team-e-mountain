package jp.jig.glasses.sample.kmp.glass

import java.io.File
import jp.jig.glasses.sample.kmp.geo.Viewpoint
import jp.jig.glasses.sample.kmp.terrain.Raycaster
import jp.jig.glasses.sample.kmp.tile.DemElevationSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 方位の目印（N/E/S/W）。**方位合わせが合っているかを目で確かめる物差し。**
 *
 * 絵は `build/ridge/` に PNG で出るので、字が読めるかは目で見る。
 */
class CardinalMarksTest {

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "data/dem/index.json").isFile }

    private val profile by lazy {
        Raycaster.scan(
            Viewpoint(latDeg = 35.9432, lonDeg = 136.1846, elevationM = 25.5),
            DemElevationSource(zoom = 10) { z, x, y ->
                File(repoRoot, "data/dem/$z/$x/$y.bin").takeIf { it.isFile }?.readBytes()
            },
        )
    }

    private fun render(azimuthDeg: Double, cardinals: Boolean = true) =
        RidgeRenderer.render(profile, azimuthDeg, altitudeDeg = 2.0, cardinals = cardinals)

    /** その列に点いている画素があるか（下辺から字の高さぶんだけ見る） */
    private fun litColumnsAtBottom(gray: ByteArray): List<Int> {
        val from = RIDGE_HEIGHT - CardinalMarks.BOTTOM_MARGIN - CardinalMarks.GLYPH_HEIGHT
        return (0 until RIDGE_WIDTH).filter { x ->
            (from until RIDGE_HEIGHT).any { y -> gray[y * RIDGE_WIDTH + x].toInt() != 0 }
        }
    }

    @Test
    fun `北を向くと N が画面の真ん中の下に出る`() {
        val gray = render(0.0)
        val columns = litColumnsAtBottom(gray)
        assertTrue("下辺に何も出ていない", columns.isNotEmpty())
        val center = (columns.first() + columns.last()) / 2.0
        assertEquals("N が画面中央に出ていない", RIDGE_WIDTH / 2.0, center, 3.0)
        val file = GlassPng.save(gray, RIDGE_WIDTH, RIDGE_HEIGHT, "build/ridge", "cardinal-north")
        println("方位マークの絵: ${file.absolutePath}")
    }

    @Test
    fun `方位マークは稜線より一段暗い`() {
        // **同じ明るさにすると、どちらが山の形なのか分からなくなる**
        val gray = render(0.0)
        val levels = gray.map { (it.toInt() and 0xFF) ushr 5 }.toSet()
        assertTrue("方位マークの階調が出ていない（$levels）", 4 in levels)
        assertTrue("稜線の最上段が出ていない（$levels）", 7 in levels)
        assertEquals("使っていない階調が混ざっている", setOf(0, 4, 7), levels)
    }

    @Test
    fun `視野に入っていない方位の字は出さない`() {
        // 方位 45° を向くと N(0°) も E(90°) も視野 35° の外。半端に切れた字は読めないので出さない
        val gray = render(45.0)
        val levels = gray.map { (it.toInt() and 0xFF) ushr 5 }.toSet()
        assertTrue("視野の外の方位マークが出ている（$levels）", 4 !in levels)
    }

    @Test
    fun `切れば方位マークは出ない`() {
        // 実機で邪魔だったときに比べられること。**同じ向きで比べる**
        val with = render(0.0)
        val without = render(0.0, cardinals = false)
        assertTrue("切っても絵が変わっていない", !with.contentEquals(without))
        val levels = without.map { (it.toInt() and 0xFF) ushr 5 }.toSet()
        assertEquals("切ったのに方位マークが残っている", setOf(0, 7), levels)
    }

    @Test
    fun `西を向くと W が出る`() {
        val gray = render(270.0)
        val columns = litColumnsAtBottom(gray)
        assertTrue("下辺に何も出ていない", columns.isNotEmpty())
        assertEquals("W が画面中央に出ていない", RIDGE_WIDTH / 2.0, (columns.first() + columns.last()) / 2.0, 3.0)
        val file = GlassPng.save(gray, RIDGE_WIDTH, RIDGE_HEIGHT, "build/ridge", "cardinal-west")
        println("方位マークの絵: ${file.absolutePath}")
    }
}
