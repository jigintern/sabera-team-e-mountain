package jp.jig.glasses.sample.kmp.glass

import java.io.File
import jp.jig.glasses.sample.kmp.catalog.PeakCatalog
import jp.jig.glasses.sample.kmp.geo.Viewpoint
import jp.jig.glasses.sample.kmp.terrain.PeakPanorama
import jp.jig.glasses.sample.kmp.tile.DemElevationSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 同梱の実データで、**どの山に名前が付くか**を固定する。
 *
 * 数字の出どころは [docs/team-e/76_terrain-measurements.md]。
 */
class PeakLabelsTest {

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "data/dem/index.json").isFile }

    private fun source() = DemElevationSource(zoom = 10) { z, x, y ->
        File(repoRoot, "data/dem/$z/$x/$y.bin").takeIf { it.isFile }?.readBytes()
    }

    private val catalog by lazy { PeakCatalog.parse(File(repoRoot, "data/peaks.json").readText()) }

    private fun panoramaAt(latDeg: Double, lonDeg: Double): PeakPanorama {
        val elevation = source()
        val ground = elevation.elevationM(latDeg, lonDeg)!!
        return PeakPanorama.scan(Viewpoint(latDeg, lonDeg, ground + 1.5), catalog, elevation)
    }

    private val sabae by lazy { panoramaAt(35.9432, 136.1846) }

    @Test
    fun `鯖江から白山の方を向くと白山に名前が付く`() {
        val placed = PeakLabels.place(sabae, azimuthDeg = 65.77, altitudeDeg = 2.0)
        println("鯖江・方位 65.77° の山名: " + placed.joinToString { "${it.label}(${"%.2f".format(it.sighted.altitudeDeg)}°)" })
        assertTrue("名前が 1 つも出ていない", placed.isNotEmpty())
        assertTrue("白山に名前が付いていない", placed.any { it.label == "白山" })
        assertTrue("枠を超えている（${placed.size}）", placed.size <= PeakLabels.SLOTS)
    }

    @Test
    fun `百名山は仰角で押し出されない`() {
        // 日野山（4.41°）など、白山（2.40°）より高く見える山が同じ視野に入る。
        // 枠を 2 つに絞っても、予約枠があるので白山が先頭に残る
        val narrow = PeakLabels.place(sabae, azimuthDeg = 65.77, altitudeDeg = 2.0, slots = 2)
        assertEquals("予約枠があるのに白山が先頭に来ていない", "白山", narrow.first().label)

        // 予約枠を外すと、仰角の高い山に押し出される（＝予約枠が効いていることの裏取り）
        val plain = PeakLabels.place(sabae, azimuthDeg = 65.77, altitudeDeg = 2.0, slots = 2, famousSlots = 0)
        assertTrue(
            "予約枠を外しても順番が変わらない。仰角順になっていない疑い",
            plain.first().sighted.altitudeDeg >= narrow.first().sighted.altitudeDeg,
        )
    }

    @Test
    fun `視野の外の山には名前を付けない`() {
        // 白山は方位 65.8°。真裏を向けば入らない
        val behind = PeakLabels.place(sabae, azimuthDeg = 245.77, altitudeDeg = 2.0)
        assertTrue("真裏を向いて白山が出ている", behind.none { it.label == "白山" })
    }

    @Test
    fun `主役は視野中心にいちばん近い山`() {
        // 白山の方位・仰角そのものを向いているので、主役は白山でなければおかしい
        val placed = PeakLabels.place(sabae, azimuthDeg = 65.77, altitudeDeg = 2.40)
        val leading = PeakLabels.nearestToCenter(placed)
        assertNotNull("主役が決まらない", leading)
        assertEquals("白山", leading!!.label)
    }

    @Test
    fun `名前は 8 枠 190 バイトに収まる`() {
        // 見える山が多い土地で予算を試す。熊谷は関東平野から上州・秩父の山が並ぶ
        val kumagaya = panoramaAt(36.1470, 139.3886)
        var withNames = 0
        for (azimuth in 0 until 360 step 15) {
            val placed = PeakLabels.place(kumagaya, azimuth.toDouble(), altitudeDeg = 1.0)
            val elements = PeakLabels.labels(placed).toCanvasElements(RIDGE_WIDTH, RIDGE_HEIGHT)
            assertTrue("枠が 8 を超えた（方位 $azimuth°）", elements.size <= CANVAS_TEXT_SLOTS)
            val bytes = elements.sumOf { it.byteSize() }
            assertTrue("$azimuth° で $bytes バイト（上限 $CANVAS_TEXT_BUDGET_BYTES）", bytes <= CANVAS_TEXT_BUDGET_BYTES)
            if (elements.isNotEmpty()) withNames++
        }
        assertTrue("どの方位でも名前が出ていない", withNames > 0)
        println("熊谷: 24 方位のうち $withNames 方位で山名が出た")
    }

    @Test
    fun `出ている名前と出ている山が食い違わない`() {
        val placed = PeakLabels.place(sabae, azimuthDeg = 65.77, altitudeDeg = 2.0)
        val labels = PeakLabels.labels(placed)
        val elements = labels.toCanvasElements(RIDGE_WIDTH, RIDGE_HEIGHT)
        val shown = labels.fittingIndices(RIDGE_WIDTH, RIDGE_HEIGHT).map { placed[it] }
        // **この一致が #37 の再発防止そのもの。** 片方だけ作り直すと必ずここが割れる
        assertEquals("枠の数が合わない", elements.size, shown.size)
        assertEquals(
            "グラスに出る文字と、根拠にする山がずれている",
            elements.map { it.text },
            shown.map { it.label },
        )
    }
}
