package jp.jig.glasses.sample.kmp.terrain

import jp.jig.glasses.sample.kmp.geo.ObservationDefaults
import jp.jig.glasses.sample.kmp.geo.Viewpoint

/**
 * 焼いた地平線を持ち、**いつ焼き直すか**を決める。
 *
 * 見通し計算は 1,656k サンプルで JVM でも 300ms かかる。首を振るたびに回すと使えない。
 * **位置が確定したときに 1 回だけ**回し、[ObservationDefaults.REBAKE_DISTANCE_M] 動いたら
 * 焼き直す。50km 先の山が画面 1 画素（0.066°）動く移動距離が約 57m なので、それが根拠。
 */
class RidgeSession(private val elevation: ElevationSource) {

    var profile: HorizonProfile? = null
        private set

    /** 焼いたときの観測地。**標高は DEM から取ったもの**で、GPS の標高ではない */
    var bakedAt: Viewpoint? = null
        private set

    /**
     * [latDeg], [lonDeg] で焼き直しが要るか。
     *
     * **標高は DEM から引く。** GPS の標高は ±20m ずれ、観測者が地面に埋まると
     * 平地そのものが壁になって稜線がまるごと壊れる（実測済み）。
     */
    fun needsBake(latDeg: Double, lonDeg: Double): Boolean {
        val baked = bakedAt ?: return true
        return Viewpoint(latDeg, lonDeg, baked.elevationM)
            .movedMoreThan(baked, ObservationDefaults.REBAKE_DISTANCE_M)
    }

    /**
     * 焼く。**重い**（JVM で約 300ms、端末は未計測）ので呼ぶ側が別スレッドへ出すこと。
     *
     * @param eyeHeightM 目の高さ。地面に立っているぶんを足す
     */
    fun bake(latDeg: Double, lonDeg: Double, eyeHeightM: Double = EYE_HEIGHT_M): HorizonProfile {
        val ground = elevation.elevationM(latDeg, lonDeg) ?: ObservationDefaults.DEFAULT_ELEVATION_M
        val from = Viewpoint(latDeg, lonDeg, ground + eyeHeightM)
        val baked = Raycaster.scan(from, elevation)
        profile = baked
        bakedAt = from
        return baked
    }

    companion object {
        /** 立っている人の目の高さ[m]。**地面の標高に足す** */
        const val EYE_HEIGHT_M = 1.5
    }
}
