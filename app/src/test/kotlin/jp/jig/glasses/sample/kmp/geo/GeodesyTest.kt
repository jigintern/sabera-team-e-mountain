package jp.jig.glasses.sample.kmp.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 実在の山で固定する。**数字は data/peaks.json の緯度経度から出したもの**なので、
 * 式を触って落ちたら式のほうが疑わしい。
 */
class GeodesyTest {

    // 鯖江（測位できないときの既定の観測地）。標高は市街地のおおよそ
    private val sabae = Viewpoint(latDeg = 35.9432, lonDeg = 136.1846, elevationM = 50.0)

    @Test
    fun `地球の丸みで沈む量が距離の 2 乗で効く`() {
        assertEquals(170.7, Geodesy.apparentDropM(50_000.0), 0.5)
        assertEquals(682.8, Geodesy.apparentDropM(100_000.0), 0.5)
        assertEquals(1536.3, Geodesy.apparentDropM(150_000.0), 0.5)
    }

    @Test
    fun `鯖江から白山が見える`() {
        val s = sabae.sight(latDeg = 36.155063, lonDeg = 136.771449, elevationM = 2702.0)
        assertEquals(57_780.0, s.distanceM, 50.0)
        assertEquals(65.77, s.azimuthDeg, 0.05)
        assertEquals(2.402, s.altitudeDeg, 0.05)
    }

    @Test
    fun `近くの低い山が遠くの高い山より高く見える`() {
        // **ラベル 7 枠を仰角だけで並べると白山が押し出される。** 百名山に枠を
        // 予約している理由がこれ（日野山 794m が白山 2702m より高く見える）
        val hakusan = sabae.sight(36.155063, 136.771449, 2702.0)
        val hinosan = sabae.sight(35.859184, 136.207491, 794.0)
        assertEquals(4.410, hinosan.altitudeDeg, 0.05)
        assertTrue(
            "日野山(${hinosan.altitudeDeg}) が白山(${hakusan.altitudeDeg}) より高く見えるはず",
            hinosan.altitudeDeg > hakusan.altitudeDeg,
        )
    }

    @Test
    fun `239km 先の富士山は地平線の下にある`() {
        // **150km で打ち切る根拠。** 3776m あっても 239km では沈む
        val fuji = sabae.sight(35.360738, 138.727373, 3776.0)
        assertEquals(238_696.0, fuji.distanceM, 200.0)
        assertTrue("仰角 ${fuji.altitudeDeg} は負のはず", fuji.altitudeDeg < 0.0)
    }

    @Test
    fun `方位へ進んでから測り直すと元の方位と距離に戻る`() {
        for (az in listOf(0.0, 47.5, 137.0, 271.3, 359.9)) {
            for (km in listOf(1.0, 25.0, 150.0)) {
                val p = Geodesy.destination(sabae.latDeg, sabae.lonDeg, az, km * 1000.0)
                assertEquals(km * 1000.0, Geodesy.distanceM(sabae.latDeg, sabae.lonDeg, p[0], p[1]), 1.0)
                assertEquals(az, Geodesy.azimuthDeg(sabae.latDeg, sabae.lonDeg, p[0], p[1]), 0.01)
            }
        }
    }

    @Test
    fun `方位の差は 180 度で折り返す`() {
        assertEquals(10.0, azimuthDeltaDeg(350.0, 0.0), 1e-9)
        assertEquals(-10.0, azimuthDeltaDeg(0.0, 350.0), 1e-9)
        assertEquals(1.0, azimuthDeltaDeg(359.5, 0.5), 1e-9)
    }

    @Test
    fun `高いところに立つと遠くまで見える`() {
        // 標高 0m から 150km 先は 1536m 以上でないと地平線に届かない
        assertEquals(1536.3, Geodesy.minimumVisibleElevationM(150_000.0, 0.0), 0.5)
        assertTrue(Geodesy.altitudeDeg(150_000.0, 0.0, 1600.0) > 0.0)
        assertTrue(Geodesy.altitudeDeg(150_000.0, 0.0, 1400.0) < 0.0)
    }

    @Test
    fun `仰角が負でも見えないとは限らない`() {
        // **2000m に立てば 150km 先の海面すら見える。** ただし目線より下なので仰角は負。
        // 「仰角が負」＝「見えない」ではない。**見えるかどうかを決めるのは
        // 手前の地形（PeakVisibility）で、仰角の符号ではない。**
        assertTrue(Geodesy.altitudeDeg(150_000.0, 2000.0, 0.0) < 0.0)
        // 平地（標高 50m）から見る限りは、負の仰角は地平線の下＝見えないと扱ってよい
        assertTrue(Geodesy.altitudeDeg(150_000.0, 50.0, 1400.0) < 0.0)
    }
}
