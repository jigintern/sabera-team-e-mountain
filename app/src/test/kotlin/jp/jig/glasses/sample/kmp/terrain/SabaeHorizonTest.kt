package jp.jig.glasses.sample.kmp.terrain

import java.io.File
import jp.jig.glasses.sample.kmp.geo.Viewpoint
import jp.jig.glasses.sample.kmp.tile.DemElevationSource
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 同梱の地形で、鯖江から実際に見通し計算を回す。
 *
 * **アプリ全体でいちばん重い処理がここ。** 実機で何秒かかるかは端末でしか分からないが、
 * JVM で桁が合っていなければ端末でも合わない。
 */
class SabaeHorizonTest {

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "data/dem/index.json").isFile }

    private fun source() = DemElevationSource(10) { z, x, y ->
        File(repoRoot, "data/dem/$z/$x/$y.bin").takeIf { it.isFile }?.readBytes()
    }

    private val sabae = Viewpoint(latDeg = 35.9432, lonDeg = 136.1846, elevationM = 50.0)

    @Test
    fun `鯖江の地平線を焼いて白山が稜線に出る`() {
        val elevation = source()
        val started = System.nanoTime()
        val profile = Raycaster.scan(sabae, elevation)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        val samplesPerRay = generateSequence(Raycaster.sampleStepM(0.0)) { it + Raycaster.sampleStepM(it) }
            .takeWhile { it <= Raycaster.MAX_DISTANCE_M }.count()
        println("見通し計算: ${profile.rayCount} 本 × 約 $samplesPerRay サンプル = 約 ${profile.rayCount * samplesPerRay / 1000} k 回 / $elapsedMs ms")

        // 白山は方位 65.77°・仰角 2.40°。**稜線がそこに立っていること**を見る
        val atHakusan = profile.altitudeDeg(65.77)
        println("方位 65.77°（白山）の稜線: $atHakusan°")
        assertTrue("白山の方位に稜線が無い（$atHakusan°）", atHakusan > 1.9)
        assertTrue("白山の方位の稜線が高すぎる（$atHakusan°）", atHakusan < 3.2)

        // **鯖江から日本海の地平線は見えない。** 西は丹生山地（16km に 588m）が塞ぐ。
        // 「海の方向だから地平線ちょうど」ではないことを実データで固定する
        val west = profile.altitudeDeg(285.0)
        println("方位 285°（丹生山地ごしの西）の稜線: $west°")
        assertTrue("西に何も立っていない（$west°）。丹生山地が抜けている", west > 1.0)
        assertTrue("西が高すぎる（$west°）。丹生山地は 2° 前後", west < 2.5)

        // 東側（白山・両白山地）のほうが西側（日本海）より高い
        val eastMax = (0..900).maxOf { profile.altitudeDeg(45.0 + it * 0.1) }
        val westMax = (0..900).maxOf { profile.altitudeDeg(225.0 + it * 0.1) }
        println("東 45〜135° の最大 $eastMax° / 西 225〜315° の最大 $westMax°")
        assertTrue("東のほうが山深いはず", eastMax > westMax)
    }

    @Test
    fun `厳密な遮蔽で見える山だけが残る`() {
        val elevation = source()
        val catalog = jp.jig.glasses.sample.kmp.catalog.PeakCatalog.parse(
            File(repoRoot, "data/peaks.json").readText(),
        )
        val within = catalog.peaks.filter {
            jp.jig.glasses.sample.kmp.geo.Geodesy.distanceM(sabae.latDeg, sabae.lonDeg, it.latDeg, it.lonDeg) <= Raycaster.MAX_DISTANCE_M
        }
        val started = System.nanoTime()
        val visible = within.filter {
            PeakVisibility.isVisible(sabae, it.latDeg, it.lonDeg, it.elevationM.toDouble(), elevation)
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        println("150km 圏 ${within.size} 座 → 見えるのは ${visible.size} 座 / $elapsedMs ms")
        println("  見える百名山: " + visible.filter { it.isFamous }.joinToString { "${it.label}(${it.elevationM}m)" })

        assertTrue("圏内に山が無い", within.size > 100)
        assertTrue("全部見えるのは遮蔽が効いていない", visible.size < within.size)
        assertTrue("1 座も見えないのは絞りすぎ", visible.isNotEmpty())
    }
}
