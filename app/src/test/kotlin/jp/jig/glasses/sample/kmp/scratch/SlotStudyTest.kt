package jp.jig.glasses.sample.kmp.scratch

import java.io.File
import jp.jig.glasses.sample.kmp.catalog.PeakCatalog
import jp.jig.glasses.sample.kmp.geo.Viewpoint
import jp.jig.glasses.sample.kmp.glass.PeakLabels
import jp.jig.glasses.sample.kmp.glass.PlacedPeak
import jp.jig.glasses.sample.kmp.glass.RIDGE_HEIGHT
import jp.jig.glasses.sample.kmp.glass.RIDGE_WIDTH
import jp.jig.glasses.sample.kmp.glass.fittingIndices
import jp.jig.glasses.sample.kmp.terrain.PeakPanorama
import jp.jig.glasses.sample.kmp.tile.DemElevationSource
import org.junit.Test

/**
 * **`PeakLabels.FAMOUS_SLOTS` を決めるための実測。** 検算ではないので何も assert しない。
 *
 * 47 都道府県庁所在地 × 72 方位 = 3,384 視野で、予約枠 N ごとに
 * 「押し出された山」と「代わりに入った百名山」の仰角差を数える。
 * 結果は `build/ridge/slots.txt` と [docs/team-e/76_terrain-measurements.md]。
 */
class SlotStudyTest {

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "data/dem/index.json").isFile }

    private fun source() = DemElevationSource(zoom = 10) { z, x, y ->
        File(repoRoot, "data/dem/$z/$x/$y.bin").takeIf { it.isFile }?.readBytes()
    }

    private val capitals = listOf(
        Triple("札幌", 43.0642, 141.3469), Triple("青森", 40.8244, 140.7400),
        Triple("盛岡", 39.7036, 141.1527), Triple("仙台", 38.2688, 140.8721),
        Triple("秋田", 39.7186, 140.1024), Triple("山形", 38.2404, 140.3633),
        Triple("福島", 37.7500, 140.4678), Triple("水戸", 36.3418, 140.4468),
        Triple("宇都宮", 36.5657, 139.8836), Triple("前橋", 36.3907, 139.0604),
        Triple("さいたま", 35.8569, 139.6489), Triple("千葉", 35.6050, 140.1233),
        Triple("東京", 35.6895, 139.6917), Triple("横浜", 35.4478, 139.6425),
        Triple("新潟", 37.9026, 139.0232), Triple("富山", 36.6953, 137.2114),
        Triple("金沢", 36.5947, 136.6256), Triple("福井", 36.0652, 136.2216),
        Triple("甲府", 35.6642, 138.5686), Triple("長野", 36.6513, 138.1810),
        Triple("岐阜", 35.3912, 136.7223), Triple("静岡", 34.9769, 138.3831),
        Triple("名古屋", 35.1802, 136.9066), Triple("津", 34.7303, 136.5086),
        Triple("大津", 35.0045, 135.8686), Triple("京都", 35.0116, 135.7681),
        Triple("大阪", 34.6863, 135.5200), Triple("神戸", 34.6913, 135.1830),
        Triple("奈良", 34.6851, 135.8048), Triple("和歌山", 34.2261, 135.1675),
        Triple("鳥取", 35.5039, 134.2383), Triple("松江", 35.4723, 133.0505),
        Triple("岡山", 34.6618, 133.9350), Triple("広島", 34.3963, 132.4596),
        Triple("山口", 34.1859, 131.4714), Triple("徳島", 34.0658, 134.5593),
        Triple("高松", 34.3401, 134.0434), Triple("松山", 33.8416, 132.7657),
        Triple("高知", 33.5597, 133.5311), Triple("福岡", 33.6064, 130.4181),
        Triple("佐賀", 33.2494, 130.2988), Triple("長崎", 32.7448, 129.8737),
        Triple("熊本", 32.7898, 130.7417), Triple("大分", 33.2382, 131.6126),
        Triple("宮崎", 31.9111, 131.4239), Triple("鹿児島", 31.5602, 130.5581),
        Triple("那覇", 26.2124, 127.6809),
    )

    /** **実際にグラスへ出る**ぶんだけで数える。枠を取っても重なりで落ちれば意味がない */
    private fun shown(placed: List<PlacedPeak>) =
        PeakLabels.labels(placed).fittingIndices(RIDGE_WIDTH, RIDGE_HEIGHT).map { placed[it] }

    @Test
    fun study() {
        val catalog = PeakCatalog.parse(File(repoRoot, "data/peaks.json").readText())
        val sb = StringBuilder()
        val gaps = Array(8) { ArrayList<Double>() }
        val famousShown = IntArray(8)
        val leadingChanged = IntArray(8)
        val examples = StringBuilder()
        var views = 0
        val started = System.nanoTime()

        for ((name, lat, lon) in capitals) {
            val elevation = source()
            val ground = elevation.elevationM(lat, lon) ?: continue
            val panorama = PeakPanorama.scan(Viewpoint(lat, lon, ground + 1.5), catalog, elevation)
            for (deg in 0 until 360 step 5) {
                val az = deg.toDouble()
                views++
                val base = shown(PeakLabels.place(panorama, az, 2.0, famousSlots = 0))
                val baseLeading = PeakLabels.nearestToCenter(base)?.label
                for (n in 1..7) {
                    val out = shown(PeakLabels.place(panorama, az, 2.0, famousSlots = n))
                    famousShown[n] += out.count { it.sighted.isFamous }
                    val gained = out.filter { g -> base.none { it.label == g.label } }
                    val lost = base.filter { b -> out.none { it.label == b.label } }
                    // 入った百名山のうち最も低いものと、落ちた山のうち最も高いものを突き合わせる
                    val gainedFamous = gained.filter { it.sighted.isFamous }.minByOrNull { it.sighted.altitudeDeg }
                    val lostTop = lost.maxByOrNull { it.sighted.altitudeDeg }
                    if (gainedFamous != null && lostTop != null) {
                        val gap = lostTop.sighted.altitudeDeg - gainedFamous.sighted.altitudeDeg
                        gaps[n] += gap
                        if (n == 4 && gap > 2.0 && examples.count { it == '\n' } < 12) {
                            examples.append(
                                "  %s 方位%3d° : %s(%.2f°) が落ちて %s(%.2f°) が入る\n".format(
                                    name, deg, lostTop.label, lostTop.sighted.altitudeDeg,
                                    gainedFamous.label, gainedFamous.sighted.altitudeDeg,
                                ),
                            )
                        }
                    }
                    if (PeakLabels.nearestToCenter(out)?.label != baseLeading) leadingChanged[n]++
                }
            }
        }

        sb.append("■ ${capitals.size} 地点 × 72 方位 = $views 視野 / ${(System.nanoTime() - started) / 1_000_000} ms\n\n")
        sb.append("■ 予約枠 N のとき、押し出された山と代わりに入った百名山の仰角差\n")
        sb.append("   N | 出た百名山 | 押し出し | 差の中央値 | 差>1° | 差>2° | 主役が変わった視野\n")
        for (n in 1..7) {
            val g = gaps[n].sorted()
            val median = if (g.isEmpty()) 0.0 else g[g.size / 2]
            sb.append(
                "  %2d | %10d | %8d | %9.2f° | %5d | %5d | %6d\n".format(
                    n, famousShown[n], g.size, median, g.count { it > 1.0 }, g.count { it > 2.0 }, leadingChanged[n],
                ),
            )
        }
        sb.append("\n■ N=4 で差が 2° を超えた例（近くの目立つ山を落として遠い百名山を出す）\n").append(examples)
        File("build/ridge").mkdirs()
        File("build/ridge/slots.txt").writeText(sb.toString())
        println(sb)
    }
}
