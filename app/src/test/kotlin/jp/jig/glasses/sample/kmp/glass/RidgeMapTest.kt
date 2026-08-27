package jp.jig.glasses.sample.kmp.glass

import java.io.File
import jp.jig.glasses.sample.kmp.catalog.PeakCatalog
import jp.jig.glasses.sample.kmp.geo.Viewpoint
import jp.jig.glasses.sample.kmp.terrain.PeakPanorama
import jp.jig.glasses.sample.kmp.terrain.Raycaster
import jp.jig.glasses.sample.kmp.tile.DemElevationSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **絵と名前を 1 つの器で運ぶ**ことを、同梱の実データで確かめる。
 *
 * 星しるべ #37（「オリオン座」と出しながら別の星座を喋る）の再発防止が主眼。
 */
class RidgeMapTest {

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "data/dem/index.json").isFile }

    private fun source() = DemElevationSource(zoom = 10) { z, x, y ->
        File(repoRoot, "data/dem/$z/$x/$y.bin").takeIf { it.isFile }?.readBytes()
    }

    /** 鯖江。標高は同梱 DEM から引いて目の高さを足す（実機と同じ取り方） */
    private val baked by lazy {
        val elevation = source()
        val ground = elevation.elevationM(35.9432, 136.1846)!!
        val from = Viewpoint(35.9432, 136.1846, ground + 1.5)
        val catalog = PeakCatalog.parse(File(repoRoot, "data/peaks.json").readText())
        Triple(Raycaster.scan(from, elevation), PeakPanorama.scan(from, catalog, elevation), from)
    }

    @Test
    fun `白山を向いて焼くと稜線と白山の名前が同じ 1 枚から出る`() {
        val (profile, panorama, from) = baked
        println("観測地: ${from.elevationM} m / 見える山 ${panorama.peaks.size} 座（百名山 ${panorama.famous.size} 座）")

        val map = RidgeMap.bake(profile, panorama, azimuthDeg = 65.77, altitudeDeg = 2.40)
        assertTrue("稜線が 1 画素も出ていない", map.gray.any { it.toInt() != 0 })
        assertTrue("バッファ超過 ${map.bufferUsageBytes} バイト", map.fitsBuffer)

        println("出る山名: " + map.shownPeaks.joinToString { "${it.label}(${it.x},${it.y})" })
        assertTrue("白山の名前が出ていない", map.shownPeaks.any { it.label == "白山" })
        assertEquals("主役が白山でない", "白山", map.leading?.label)
    }

    @Test
    fun `出る山は視野に入った山の部分集合`() {
        val (profile, panorama, _) = baked
        val map = RidgeMap.bake(profile, panorama, azimuthDeg = 65.77, altitudeDeg = 2.40)
        assertTrue("落ちた山が増えている", map.shownPeaks.size <= map.peaks.size)
        assertTrue("枠を超えている", map.shownPeaks.size <= CANVAS_TEXT_SLOTS)
        assertTrue("視野に無い山が出ている", map.shownPeaks.all { placed -> map.peaks.any { it === placed } })
    }

    @Test
    fun `主役は必ずグラスに出ている山`() {
        val (profile, panorama, _) = baked
        // 山名が出るのは東側。1 周まわして、主役が「出ていない山」になる向きが無いことを見る
        for (azimuth in 0 until 360 step 5) {
            val map = RidgeMap.bake(profile, panorama, azimuth.toDouble(), altitudeDeg = 2.0)
            val leading = map.leading ?: continue
            assertTrue(
                "方位 $azimuth° で、グラスに出ていない山が主役になっている（${leading.label}）",
                map.shownPeaks.any { it === leading },
            )
        }
    }

    @Test
    fun `名前は稜線の線に重ならない`() {
        val (profile, panorama, _) = baked
        val map = RidgeMap.bake(profile, panorama, azimuthDeg = 65.77, altitudeDeg = 2.40)
        val leading = map.leading
        assertNotNull("主役が決まらない", leading)
        // ラベルの枠の下辺が山頂より上にあること。線幅は 2〜3px なので掛からない
        val bottom = leading!!.y + CANVAS_LABEL_HEIGHT / 2
        val summit = leading.y + PeakLabels.LABEL_OFFSET_PX
        assertTrue("ラベルが山頂に掛かっている（下辺 $bottom / 山頂 $summit）", bottom < summit)
    }
}
