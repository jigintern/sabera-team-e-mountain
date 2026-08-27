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

    /**
     * **グラスに出る 1 画面ぶんを目で見るために書き出す。**
     *
     * 文字そのものはファームが描くのでここでは出せない。代わりに**枠の矩形と山頂の印**を
     * 描く。見たいのは「名前が稜線のどこに乗るか」「枠どうしがぶつかっていないか」で、
     * どちらも矩形で分かる。
     */
    private fun savePanelPreview(map: RidgeMap, name: String) {
        val panel = ByteArray(PANEL_WIDTH * PANEL_HEIGHT)
        val offsetX = (PANEL_WIDTH - map.width) / 2
        val offsetY = (PANEL_HEIGHT - map.height) / 2
        for (y in 0 until map.height) {
            for (x in 0 until map.width) {
                panel.plot(PANEL_WIDTH, PANEL_HEIGHT, offsetX + x, offsetY + y, map.gray[y * map.width + x].toInt() and 0xFF)
            }
        }
        for (element in map.labels.toCanvasElements(map.width, map.height)) {
            val x0 = element.x
            val y0 = element.y
            val x1 = element.x + element.width - 1
            val y1 = element.y + element.height - 1
            panel.line(PANEL_WIDTH, PANEL_HEIGHT, x0, y0, x1, y0, Ink.CARDINAL)
            panel.line(PANEL_WIDTH, PANEL_HEIGHT, x0, y1, x1, y1, Ink.CARDINAL)
            panel.line(PANEL_WIDTH, PANEL_HEIGHT, x0, y0, x0, y1, Ink.CARDINAL)
            panel.line(PANEL_WIDTH, PANEL_HEIGHT, x1, y0, x1, y1, Ink.CARDINAL)
        }
        for (peak in map.shownPeaks) {
            // 山頂そのものに縦の印。ラベルの下辺との隙間が目で見える
            val x = offsetX + peak.x
            val y = offsetY + peak.y + PeakLabels.LABEL_OFFSET_PX
            panel.line(PANEL_WIDTH, PANEL_HEIGHT, x, y - 8, x, y + 8, Ink.RIDGE_LINE)
        }
        val file = GlassPng.save(panel, PANEL_WIDTH, PANEL_HEIGHT, "build/ridge", name)
        println("プレビュー: ${file.absolutePath}")
        println("  枠: " + map.shownPeaks.joinToString { "${it.label}@(${it.x},${it.y})" })
    }

    @Test
    fun `グラス 1 画面ぶんのプレビューを書き出す`() {
        val (profile, panorama, _) = baked
        savePanelPreview(RidgeMap.bake(profile, panorama, 65.77, 2.40), "panel-hakusan")
        savePanelPreview(RidgeMap.bake(profile, panorama, 167.5, 3.5), "panel-hinosan")
        savePanelPreview(RidgeMap.bake(profile, panorama, 90.0, 2.0), "panel-east")
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
