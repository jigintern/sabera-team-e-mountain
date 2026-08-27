package jp.jig.glasses.sample.kmp.terrain

import jp.jig.glasses.sample.kmp.geo.Viewpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 作り物の地形で見通し計算を固定する。**実データを使わないので数字が動かない。** */
class RaycasterTest {

    private val here = Viewpoint(latDeg = 35.0, lonDeg = 135.0, elevationM = 0.0)

    /** 東経 135.1 より東だけ 500m の壁。西は平ら。 */
    private val wallToTheEast = ElevationSource { _, lon -> if (lon > 135.1) 500.0 else 0.0 }

    @Test
    fun `東の壁が仰角として出る`() {
        val profile = Raycaster.scan(here, wallToTheEast, maxDistanceM = 50_000.0)
        // 壁までは 0.1° ぶん＝約 9.1km。500m の壁は約 3.1° に立つ
        assertEquals(3.10, profile.altitudeDeg(90.0), 0.1)
        assertTrue("壁までの距離は約 9.1km", Math.abs(profile.distanceM(90.0) - 9_119.0) < 300.0)
    }

    @Test
    fun `平らな地面は地平線とほぼ同じ高さになる`() {
        val profile = Raycaster.scan(here, wallToTheEast, maxDistanceM = 50_000.0)
        assertEquals(0.0, profile.altitudeDeg(270.0), 0.05)
    }

    @Test
    fun `データが無い方位は地平線として扱う`() {
        val nothing = ElevationSource { _, _ -> null }
        val profile = Raycaster.scan(here, nothing, maxDistanceM = 20_000.0)
        assertEquals(-10.0, profile.altitudeDeg(0.0), 1e-9)
    }

    @Test
    fun `方位が一周して折り返す`() {
        val profile = Raycaster.scan(here, wallToTheEast, maxDistanceM = 50_000.0)
        assertEquals(profile.altitudeDeg(0.0), profile.altitudeDeg(360.0), 1e-9)
        assertEquals(profile.altitudeDeg(90.0), profile.altitudeDeg(-270.0), 1e-9)
    }

    @Test
    fun `既定の刻みは 3600 本`() {
        val profile = Raycaster.scan(here, ElevationSource { _, _ -> 0.0 }, maxDistanceM = 1_000.0)
        assertEquals(3600, profile.rayCount)
        assertEquals(0.1, profile.stepDeg, 1e-9)
    }
}
