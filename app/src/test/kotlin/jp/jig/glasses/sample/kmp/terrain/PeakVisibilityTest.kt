package jp.jig.glasses.sample.kmp.terrain

import jp.jig.glasses.sample.kmp.geo.Geodesy
import jp.jig.glasses.sample.kmp.geo.Viewpoint
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **星に無い問題なので、ここが落ちると実景と食い違う。**
 * 手前の尾根が奥の山を隠す状況を作って固定する。
 */
class PeakVisibilityTest {

    private val here = Viewpoint(latDeg = 35.0, lonDeg = 135.0, elevationM = 0.0)

    /** 真東 20km の 1000m 峰。 */
    private val peak = Geodesy.destination(35.0, 135.0, 90.0, 20_000.0)

    /** 真東 [atM] の位置に高さ [heightM] の尾根を置く。他は平ら。 */
    private fun ridge(atM: Double, heightM: Double) = ElevationSource { lat, lon ->
        val d = Geodesy.distanceM(here.latDeg, here.lonDeg, lat, lon)
        if (d in (atM - 300.0)..(atM + 300.0)) heightM else 0.0
    }

    @Test
    fun `手前の高い尾根は奥の山を隠す`() {
        // 5km の 400m 尾根は仰角 4.56°、20km の 1000m 峰は 2.79°
        assertFalse(PeakVisibility.isVisible(here, peak[0], peak[1], 1000.0, ridge(5_000.0, 400.0)))
    }

    @Test
    fun `手前の低い尾根は奥の山を隠さない`() {
        // 5km の 200m 尾根は仰角 2.27° で、山の 2.79° より低い
        assertTrue(PeakVisibility.isVisible(here, peak[0], peak[1], 1000.0, ridge(5_000.0, 200.0)))
    }

    @Test
    fun `山そのものの斜面で隠れたことにしない`() {
        // 的の直前に山体がある状況。SUMMIT_MARGIN_M が無いとここで自分に隠される
        assertTrue(PeakVisibility.isVisible(here, peak[0], peak[1], 1000.0, ridge(19_900.0, 990.0)))
    }

    @Test
    fun `データが無い区間は遮らない`() {
        assertTrue(PeakVisibility.isVisible(here, peak[0], peak[1], 1000.0, ElevationSource { _, _ -> null }))
    }

    @Test
    fun `打ち切り距離より遠い山は見えない扱い`() {
        val far = Geodesy.destination(35.0, 135.0, 90.0, 200_000.0)
        assertFalse(PeakVisibility.isVisible(here, far[0], far[1], 3000.0, ElevationSource { _, _ -> 0.0 }))
    }
}
