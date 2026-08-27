package jp.jig.glasses.sample.kmp.scratch

import java.io.File
import jp.jig.glasses.sample.kmp.alignment.RidgeAlignment
import jp.jig.glasses.sample.kmp.catalog.PeakCatalog
import jp.jig.glasses.sample.kmp.geo.Viewpoint
import jp.jig.glasses.sample.kmp.geo.projectionScale
import jp.jig.glasses.sample.kmp.glass.RIDGE_WIDTH
import jp.jig.glasses.sample.kmp.terrain.PeakPanorama
import jp.jig.glasses.sample.kmp.tile.DemElevationSource
import kotlin.math.atan
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **現地メモ。実機で方位合わせをするときに手元に置く表を吐く。**
 *
 * 方位合わせは「実物が見えている山を選んで、グラスの印に入れる」ので、
 * **その場所から何がどの方位に見えるかを先に知っていないと始められない。**
 * 出力は `./gradlew :app:testDebugUnitTest --tests '*FieldNotesTest*' -i` で読む。
 *
 * 画角の測定に**2 座目は要らない**（[RidgeAlignment.fovDegFrom] は峰 1 座しか見ない）。
 * 同じ山を首を振って印へ寄せればよく、そのほうが取り違えない
 * ——確かめは `RidgeAlignmentTest.同じ山を首を振って印に入れても画角が復元できる`。
 */
class FieldNotesTest {

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "data/dem/index.json").isFile }

    private val catalog by lazy { PeakCatalog.parse(File(repoRoot, "data/peaks.json").readText()) }

    private fun panoramaAt(latDeg: Double, lonDeg: Double): Pair<Double, PeakPanorama> {
        val elevation = DemElevationSource(zoom = 10) { z, x, y ->
            File(repoRoot, "data/dem/$z/$x/$y.bin").takeIf { it.isFile }?.readBytes()
        }
        val ground = elevation.elevationM(latDeg, lonDeg)!!
        val from = Viewpoint(latDeg, lonDeg, ground + 1.5)
        return ground to PeakPanorama.scan(from, catalog, elevation)
    }

    /** 印へ寄せるのに首を何度振るか */
    private fun turnToMarkDeg(fovDeg: Double): Double {
        val k = projectionScale(RIDGE_WIDTH, fovDeg)
        val markOffset = RIDGE_WIDTH * RidgeAlignment.EDGE_MARK_RATIO - RIDGE_WIDTH / 2.0
        return 2.0 * Math.toDegrees(atan(markOffset / k / 2.0))
    }

    @Test
    fun `方位合わせに使える山の表を出す`() {
        println("印へ寄せる首振り: 画角 25°→${"%.1f".format(turnToMarkDeg(25.0))}° / " +
            "35°→${"%.1f".format(turnToMarkDeg(35.0))}° / 45°→${"%.1f".format(turnToMarkDeg(45.0))}°")
        println("（画角が未実測なので、印に入るまで振る量もこの幅で動く）\n")

        for ((name, lat, lon) in PLACES) {
            val (ground, panorama) = panoramaAt(lat, lon)
            println("=== $name（地面 ${"%.1f".format(ground)}m・見える山 ${panorama.peaks.size} 座" +
                "・百名山 ${panorama.famous.size} 座）")
            if (panorama.peaks.isEmpty()) {
                println("  ** 見える山が無い。この場所では方位合わせができない **\n")
                continue
            }
            println("  方位     仰角    距離     山名")
            for (p in panorama.peaks) {
                println(
                    "  ${"%6.2f".format(p.azimuthDeg)}°  ${"%5.2f".format(p.altitudeDeg)}°  " +
                        "${"%5.1f".format(p.distanceM / 1000)}km  ${p.label}" +
                        if (p.isFamous) "【百名山】" else "",
                )
            }
            // **取り違えの危険。** 判定は本番と同じ [PeakPanorama.confusable] を使う
            // ——表とアプリが違う答えを出したら、この表は現地で信じられない
            val confusable = panorama.peaks.filter { it in panorama.confusable }
            if (confusable.isNotEmpty()) {
                println(
                    "  取り違えやすい（方位が ${PeakPanorama.CONFUSABLE_DEG}° 以内に別の山）: " +
                        confusable.joinToString { it.label },
                )
            }
            val safe = panorama.peaks.filter { panorama.safeToAlignOn(it) }
            println("  合わせに向く: " + if (safe.isEmpty()) "無し" else safe.joinToString { it.label })
            println()
        }
    }

    @Test
    fun `鯖江で方位合わせに使える山がある`() {
        val (_, panorama) = panoramaAt(35.9432, 136.1846)
        assertTrue("鯖江で山が 1 座も見えない", panorama.peaks.isNotEmpty())
        // 白山が見えることは段階3 で確認済み。ここでは合わせの入口が成立するかだけ見る
        assertTrue("鯖江から白山が見えない", panorama.peaks.any { it.label == "白山" })
    }

    /**
     * **鯖江の白山は方位合わせに使ってはいけない。**
     *
     * 1.06° 隣に白山釈迦岳、2.10° 隣に七倉山が居る。画角 35° なら 1° = 15px なので、
     * 実物を見て選り分けるのは無理がある。取り違えたまま合わせると
     * **1〜2° ずれた較正が「成功」として残る**（範囲外判定にも掛からない）。
     */
    @Test
    fun `鯖江の白山は取り違えやすい側に入る`() {
        val (_, panorama) = panoramaAt(35.9432, 136.1846)
        val hakusan = panorama.peaks.first { it.label == "白山" }
        assertTrue("白山が取り違えやすい側に入っていない", hakusan in panorama.confusable)
        assertTrue("白山が合わせに向くと言っている", !panorama.safeToAlignOn(hakusan))

        // 日野山は近くて高く、隣に何も無い。**鯖江でいちばん安全な合わせ先**
        val hinosan = panorama.peaks.first { it.label == "日野山" }
        assertTrue("日野山が合わせに向かないと言っている", panorama.safeToAlignOn(hinosan))
    }

    private companion object {
        val PLACES = listOf(
            Triple("鯖江", 35.9432, 136.1846),
            Triple("福井市", 36.0652, 136.2196),
            Triple("甲府", 35.6642, 138.5686),
        )
    }
}
