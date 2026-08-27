package jp.jig.glasses.sample.kmp.glass

import java.io.File
import jp.jig.glasses.sample.kmp.geo.Viewpoint
import jp.jig.glasses.sample.kmp.terrain.Raycaster
import jp.jig.glasses.sample.kmp.tile.DemElevationSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 同梱の地形から稜線を焼く。
 *
 * **絵は目で見ないと分からない**ので、PNG も書き出す（`build/ridge/`）。
 * グラスは緑 8 階調・黒は透明なので、見た目に合わせて緑で出す。
 */
class RidgeRendererTest {

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "data/dem/index.json").isFile }

    private val sabae = Viewpoint(latDeg = 35.9432, lonDeg = 136.1846, elevationM = 25.5)

    private val profile by lazy {
        Raycaster.scan(
            sabae,
            DemElevationSource(
                zoom = 10,
                bytes = { z, x, y -> File(repoRoot, "data/dem/$z/$x/$y.bin").takeIf { it.isFile }?.readBytes() },
            ),
        )
    }

    @Test
    fun `白山の方向を向くと稜線が画面に出る`() {
        val gray = RidgeRenderer.render(profile, azimuthDeg = 65.77, altitudeDeg = 2.0)
        val lit = gray.count { it.toInt() != 0 }
        assertTrue("稜線が 1 画素も出ていない", lit > 0)
        assertTrue("画面のほとんどが塗られている（$lit 画素）", lit < gray.size / 4)

        // **最上段だけを使う。** 中間階調は屋外で空に負ける
        val levels = gray.map { (it.toInt() and 0xFF) ushr 5 }.toSet()
        assertEquals(setOf(0, 7), levels)
        save(gray, "hakusan")
    }

    @Test
    fun `真上を向くと稜線は画面から外れる`() {
        val gray = RidgeRenderer.render(profile, azimuthDeg = 65.77, altitudeDeg = 60.0)
        assertEquals(0, gray.count { it.toInt() != 0 })
    }

    @Test
    fun `首を傾けると稜線も傾く`() {
        val flat = columnOfTopmostLit(RidgeRenderer.render(profile, 65.77, 2.0, rollDeg = 0.0))
        val tilted = columnOfTopmostLit(RidgeRenderer.render(profile, 65.77, 2.0, rollDeg = 20.0))
        assertTrue("傾けても稜線が動いていない", flat != tilted)
        save(RidgeRenderer.render(profile, 65.77, 2.0, rollDeg = 20.0), "hakusan-roll20")
    }

    @Test
    fun `方位ごとに違う絵になる`() {
        val east = RidgeRenderer.render(profile, 65.77, 2.0)
        val west = RidgeRenderer.render(profile, 285.0, 2.0)
        assertTrue("東と西で同じ絵が出ている", !east.contentEquals(west))
        save(west, "tanyu-west")
        save(RidgeRenderer.render(profile, 167.5, 3.0), "hinosan-south")
    }

    /** いちばん上に点いている画素の列。稜線の形が動いたかを見るのに使う。 */
    private fun columnOfTopmostLit(gray: ByteArray): Int {
        val index = gray.indexOfFirst { it.toInt() != 0 }
        return if (index < 0) -1 else index % RIDGE_WIDTH
    }

    private fun save(gray: ByteArray, name: String) {
        val file = GlassPng.save(gray, RIDGE_WIDTH, RIDGE_HEIGHT, "build/ridge", name)
        println("稜線の絵: ${file.absolutePath}")
    }

    /**
     * **大気差を二重に掛けていないことの歯止め。**
     *
     * 地上の的の大気差は [jp.jig.glasses.sample.kmp.geo.Geodesy.apparentDropM] の
     * 屈折係数 k=0.13 に入りきっている。星のための
     * [jp.jig.glasses.sample.kmp.geo.apparentAltitudeDeg] を描くときに重ねると、
     * 地平線で +0.48°・白山の 2.4° で +0.26° 持ち上がり、しかも**低いほど強く効くので
     * 稜線の形そのものが縦に 8% 縮む**。実景に重ねるのが目的なので、これは効く。
     *
     * 向いた仰角ちょうどの稜線は、**画面のちょうど真ん中**を通らなければならない。
     */
    @Test
    fun `向いた仰角そのままの稜線は画面の中央を通る`() {
        val az = 65.77
        val gray = RidgeRenderer.render(profile, azimuthDeg = az, altitudeDeg = profile.altitudeDeg(az))
        val center = RIDGE_WIDTH / 2
        val rows = (0 until RIDGE_HEIGHT).filter { gray[it * RIDGE_WIDTH + center].toInt() != 0 }
        assertTrue("中央の列に稜線が出ていない", rows.isNotEmpty())
        // 線には幅があるので帯の中心で見る
        val drawn = (rows.first() + rows.last()) / 2.0
        val pxPerDeg = RIDGE_HEIGHT / verticalFovDeg(RIDGE_WIDTH, RIDGE_HEIGHT, 35.0)
        // 二重掛けだと +0.26° ぶん（約 4px）上へずれる。1px は投影とレイ刻みの端数
        assertEquals("稜線が中央から ${"%.2f".format((RIDGE_HEIGHT / 2.0 - drawn) / pxPerDeg)}° ずれている",
            RIDGE_HEIGHT / 2.0, drawn, 2.0)
    }

    /** 縦の画角[度]。ステレオ投影なので横 × 縦横比ではない */
    private fun verticalFovDeg(width: Int, height: Int, fovDeg: Double): Double {
        val k = jp.jig.glasses.sample.kmp.geo.projectionScale(width, fovDeg)
        return 4.0 * Math.toDegrees(Math.atan(height / (4.0 * k)))
    }
}
