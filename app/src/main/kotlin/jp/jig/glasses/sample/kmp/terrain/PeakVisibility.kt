package jp.jig.glasses.sample.kmp.terrain

import jp.jig.glasses.sample.kmp.geo.Geodesy
import jp.jig.glasses.sample.kmp.geo.Viewpoint

/**
 * 山が手前の地形に隠れていないかを見る。**星に無い問題。**
 *
 * **地平線プロファイルだけでは足りない。** 手前の低い尾根の上に、その裏の高い山より
 * 低く見えている山があり得るので、最大仰角の一致判定では取りこぼす。
 * 山ごとに個別のレイマーチをする。
 *
 * 150km 圏の山は概ね 100〜200 座なので、200 × 600 = 12 万サンプルで済む
 * （地平線プロファイルの 1/36）。
 */
object PeakVisibility {

    /**
     * 山頂の手前どれだけを見ないか[m]。
     *
     * **これが無いと山は自分の斜面に隠される。** 山頂の直前は山体そのものなので、
     * 標高が山頂に迫っていて当たり前。
     */
    const val SUMMIT_MARGIN_M = 500.0

    /**
     * [from] から山（[latDeg], [lonDeg], [elevationM]）が見えるか。
     *
     * 見えない条件は 2 つだけ:
     * - [Raycaster.MAX_DISTANCE_M] より遠い
     * - 手前の地形の仰角が、山の仰角以上になる区間がある
     *
     * **データが無い区間は遮らない。** 海のタイルは存在しないので、
     * 無いことを「高い壁がある」と読むと海越しの山が全部消える。
     */
    fun isVisible(
        from: Viewpoint,
        latDeg: Double,
        lonDeg: Double,
        elevationM: Double,
        elevation: ElevationSource,
    ): Boolean {
        val distance = Geodesy.distanceM(from.latDeg, from.lonDeg, latDeg, lonDeg)
        if (distance > Raycaster.MAX_DISTANCE_M) return false

        val target = Geodesy.altitudeDeg(distance, from.elevationM, elevationM)
        val azimuth = Geodesy.azimuthDeg(from.latDeg, from.lonDeg, latDeg, lonDeg)
        val limit = distance - SUMMIT_MARGIN_M
        val point = DoubleArray(2)

        var d = Raycaster.MIN_DISTANCE_M
        while (d < limit) {
            Geodesy.destinationInto(from.latDeg, from.lonDeg, azimuth, d, point)
            val h = elevation.elevationM(point[0], point[1])
            if (h != null && Geodesy.altitudeDeg(d, from.elevationM, h) >= target) return false
            d += Raycaster.sampleStepM(d)
        }
        return true
    }
}
