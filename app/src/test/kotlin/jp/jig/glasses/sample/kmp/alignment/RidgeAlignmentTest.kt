package jp.jig.glasses.sample.kmp.alignment

import jp.jig.glasses.sample.kmp.geo.Basis
import jp.jig.glasses.sample.kmp.geo.apparentAltitudeDeg
import jp.jig.glasses.sample.kmp.geo.azimuthFromYaw
import jp.jig.glasses.sample.kmp.geo.enu
import jp.jig.glasses.sample.kmp.geo.headingOffsetFor
import jp.jig.glasses.sample.kmp.geo.project
import jp.jig.glasses.sample.kmp.geo.projectionScale
import jp.jig.glasses.sample.kmp.glass.RIDGE_HEIGHT
import jp.jig.glasses.sample.kmp.glass.RIDGE_WIDTH
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 稜線合わせ。**画角を実測で埋めるための逆算**が正しく閉じることを固定する。
 *
 * 検算は「本当の画角 F で描いたら峰が画面の x に出る」を作り、
 * **そこから F を復元できるか**で見る。往復が閉じなければ較正は嘘をつく。
 */
class RidgeAlignmentTest {

    /** 本当の画角が [fovDeg] のとき、その峰が画面のどこに出るか */
    private fun screenXOf(
        peakAzimuthDeg: Double,
        peakAltitudeDeg: Double,
        yawDeg: Double,
        pitchDeg: Double,
        headingOffsetDeg: Double,
        fovDeg: Double,
        rollDeg: Double = 0.0,
    ): Double {
        val basis = Basis(azimuthFromYaw(yawDeg, headingOffsetDeg), pitchDeg, rollDeg)
        val k = projectionScale(RIDGE_WIDTH, fovDeg)
        val q = project(enu(peakAzimuthDeg, apparentAltitudeDeg(peakAltitudeDeg)), basis, k, RIDGE_WIDTH, RIDGE_HEIGHT)
        return requireNotNull(q)[0]
    }

    @Test
    fun `中央に入れた峰から方位オフセットが出る`() {
        // 白山（方位 65.77°）を中央に入れて止めた。そのときのヨーが -20°
        val hold = RidgeAlignment.Hold(
            peakAzimuthDeg = 65.77, peakAltitudeDeg = 2.40,
            yawDeg = -20.0, pitchDeg = 2.40, markX = RIDGE_WIDTH / 2.0,
        )
        val offset = RidgeAlignment.headingOffsetFrom(hold)
        // このオフセットで戻すと、視線の方位が白山そのものになる
        assertEquals(65.77, azimuthFromYaw(hold.yawDeg, offset), 1e-9)
    }

    @Test
    fun `端に入れた峰から本当の画角が復元できる`() {
        val trueFov = 28.5 // 仮値の 35° とは違う値を「本当の画角」に置く
        val offset = headingOffsetFor(65.77, -20.0)
        // 中央は白山。端の印には、そこから 8° 東の峰を入れる
        val edgeAz = 65.77 + 8.0
        val markX = screenXOf(edgeAz, 2.0, -20.0, 2.40, offset, trueFov)

        val hold = RidgeAlignment.Hold(
            peakAzimuthDeg = edgeAz, peakAltitudeDeg = 2.0,
            yawDeg = -20.0, pitchDeg = 2.40, markX = markX,
        )
        val fov = RidgeAlignment.fovDegFrom(hold, offset, RIDGE_WIDTH, RIDGE_HEIGHT)
        assertEquals("画角が復元できない", trueFov, fov!!, 1e-6)
    }

    @Test
    fun `首を傾けていても画角は復元できる`() {
        val trueFov = 40.0
        val offset = headingOffsetFor(65.77, -20.0)
        val edgeAz = 65.77 - 9.0
        val markX = screenXOf(edgeAz, 1.5, -20.0, 2.0, offset, trueFov, rollDeg = 12.0)

        val hold = RidgeAlignment.Hold(
            peakAzimuthDeg = edgeAz, peakAltitudeDeg = 1.5,
            yawDeg = -20.0, pitchDeg = 2.0, rollDeg = 12.0, markX = markX,
        )
        // 傾きを [Basis] が織り込むので、水平面の近似では出ない値もそのまま出る
        assertEquals(trueFov, RidgeAlignment.fovDegFrom(hold, offset, RIDGE_WIDTH, RIDGE_HEIGHT)!!, 1e-6)
    }

    @Test
    fun `倍率と画角の往復が閉じる`() {
        for (fov in listOf(15.0, 25.0, 35.0, 50.0, 70.0)) {
            val k = projectionScale(RIDGE_WIDTH, fov)
            assertEquals(fov, RidgeAlignment.fovDegForScale(k, RIDGE_WIDTH), 1e-9)
        }
    }

    @Test
    fun `中央で止めても画角は決まらない`() {
        val offset = headingOffsetFor(65.77, -20.0)
        val hold = RidgeAlignment.Hold(
            peakAzimuthDeg = 65.77, peakAltitudeDeg = 2.40,
            yawDeg = -20.0, pitchDeg = 2.40, markX = RIDGE_WIDTH / 2.0,
        )
        // 中心では 0 で割ることになる。**答えを作らずに諦める**
        assertNull(RidgeAlignment.fovDegFrom(hold, offset, RIDGE_WIDTH, RIDGE_HEIGHT))
    }

    @Test
    fun `山を取り違えたら画角として受け付けない`() {
        val offset = headingOffsetFor(65.77, -20.0)
        // 印は右にあるのに、入れた峰は左（西）にある。符号が合わない = 取り違え
        val hold = RidgeAlignment.Hold(
            peakAzimuthDeg = 65.77 - 10.0, peakAltitudeDeg = 2.0,
            yawDeg = -20.0, pitchDeg = 2.40,
            markX = RIDGE_WIDTH * RidgeAlignment.EDGE_MARK_RATIO,
        )
        assertNull(RidgeAlignment.fovDegFrom(hold, offset, RIDGE_WIDTH, RIDGE_HEIGHT))
    }

    @Test
    fun `極端な画角は捨てる`() {
        val offset = headingOffsetFor(65.77, -20.0)
        // 0.2° しか離れていない峰を画面の端に入れた = 画角 1° 相当。実機ではあり得ない
        val hold = RidgeAlignment.Hold(
            peakAzimuthDeg = 65.77 + 0.2, peakAltitudeDeg = 2.4,
            yawDeg = -20.0, pitchDeg = 2.40,
            markX = RIDGE_WIDTH * RidgeAlignment.EDGE_MARK_RATIO,
        )
        assertNull(RidgeAlignment.fovDegFrom(hold, offset, RIDGE_WIDTH, RIDGE_HEIGHT))
    }

    @Test
    fun `片方だけ取れたら取れたぶんだけ返す`() {
        val center = RidgeAlignment.Hold(
            peakAzimuthDeg = 65.77, peakAltitudeDeg = 2.40,
            yawDeg = -20.0, pitchDeg = 2.40, markX = RIDGE_WIDTH / 2.0,
        )
        val result = RidgeAlignment.estimate(
            center = center, edge = null,
            width = RIDGE_WIDTH, height = RIDGE_HEIGHT,
            fallbackHeadingOffsetDeg = 0.0, fallbackFovDeg = 35.0,
        )
        assertTrue("方位が実測になっていない", result.headingMeasured)
        assertFalse("画角まで実測だと言っている", result.fovMeasured)
        assertEquals("画角が仮値のままでない", 35.0, result.fovDeg, 0.0)
        assertFalse(result.edgeRejected)
    }

    @Test
    fun `合っていれば残差はゼロ`() {
        val hold = RidgeAlignment.Hold(
            peakAzimuthDeg = 65.77, peakAltitudeDeg = 2.40,
            yawDeg = -20.0, pitchDeg = 2.40, markX = RIDGE_WIDTH / 2.0,
        )
        val offset = RidgeAlignment.headingOffsetFrom(hold)
        assertEquals(0.0, RidgeAlignment.residualDeg(hold, offset), 1e-9)
        // 5° ずれたオフセットなら残差も 5°
        assertEquals(5.0, RidgeAlignment.residualDeg(hold, offset + 5.0), 1e-9)
    }
}
