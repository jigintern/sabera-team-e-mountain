package jp.jig.glasses.sample.kmp.glass

import jp.jig.glasses.sample.kmp.geo.ObservationDefaults
import jp.jig.glasses.sample.kmp.terrain.HorizonProfile
import jp.jig.glasses.sample.kmp.terrain.PeakPanorama

/**
 * 名前を置く位置。**画像には焼かず、キャンバスのテキスト枠として重ねる。**
 *
 * 座標は画像の中（左上原点・y は下向き）。パネルへの移し替えは
 * [List<Label>.toCanvasElements] がやる。
 */
data class Label(val text: String, val x: Int, val y: Int)

/**
 * グラスへ送る稜線 1 枚ぶん。**絵と名前を 1 つの器で運ぶ。**
 *
 * 星しるべは絵とラベルを別々に計算していて、「オリオン座」と出しながら別の星座を喋る
 * 事故を踏んだ（#37）。山でも同じで、[peaks] は焼いた [gray] と**必ず同じ向き・同じ瞬間**の
 * ものでなければならない。だから作り口は [bake] 1 つだけにしてある。
 */
class RidgeMap(
    val width: Int,
    val height: Int,
    val gray: ByteArray,
    val peaks: List<PlacedPeak>,
) {
    /** 190 バイトの予算に掛ける前のラベル。並びは予算を取る順。 */
    val labels: List<Label> get() = PeakLabels.labels(peaks)

    /** SDK のバッファをこの 1 枚がどれだけ使うか。**送る前に数える。** */
    val bufferUsageBytes: Int by lazy { canvasBufferUsageBytes(width, height, gray) }

    /** 上限（[CANVAS_IMAGE_BUFFER_BYTES]）に収まっているか。溢れると SDK が黙って弾く */
    val fitsBuffer: Boolean get() = bufferUsageBytes <= CANVAS_IMAGE_BUFFER_BYTES

    /**
     * **実際にグラスへ出る**山だけ。重なりと 190 バイトで落ちたぶんを除いてある。
     *
     * [peaks] のほうは「視野に入っていて枠を取れた山」で、そのうち何座かは必ず落ちる。
     */
    val shownPeaks: List<PlacedPeak> by lazy {
        labels.fittingIndices(width, height).map { peaks[it] }
    }

    /**
     * 画面の主役。**視野中心にいちばん近い山**。
     *
     * **出ている名前の中から選ぶ。** 落ちた名前を主役にすると、
     * グラスに出ていない山を指して喋ることになる。
     */
    val leading: PlacedPeak? get() = PeakLabels.nearestToCenter(shownPeaks, width, height)

    companion object {
        /**
         * 稜線と山名を**同じ向きから 1 回で**焼く。
         *
         * [RidgeRenderer.render] と [PeakLabels.place] を別々に呼ばせないための入口。
         * 引数はここで 1 度だけ受けて両方へ配るので、**絵と名前がずれようがない**。
         *
         * **重い**（実機で約 300ms 見当）ので、呼ぶ側が別スレッドへ出すこと。
         */
        fun bake(
            profile: HorizonProfile,
            panorama: PeakPanorama,
            azimuthDeg: Double,
            altitudeDeg: Double,
            rollDeg: Double = 0.0,
            width: Int = RIDGE_WIDTH,
            height: Int = RIDGE_HEIGHT,
            fovDeg: Double = ObservationDefaults.FOV_DEG,
        ): RidgeMap = RidgeMap(
            width = width,
            height = height,
            gray = RidgeRenderer.render(profile, azimuthDeg, altitudeDeg, rollDeg, width, height, fovDeg),
            peaks = PeakLabels.place(panorama, azimuthDeg, altitudeDeg, rollDeg, width, height, fovDeg),
        )
    }
}
