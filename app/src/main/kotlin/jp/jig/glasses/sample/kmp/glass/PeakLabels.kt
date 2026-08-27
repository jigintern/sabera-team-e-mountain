package jp.jig.glasses.sample.kmp.glass

import jp.jig.glasses.sample.kmp.geo.Basis
import jp.jig.glasses.sample.kmp.geo.Geodesy
import jp.jig.glasses.sample.kmp.geo.ObservationDefaults
import jp.jig.glasses.sample.kmp.geo.enu
import jp.jig.glasses.sample.kmp.geo.project
import jp.jig.glasses.sample.kmp.geo.projectionScale
import jp.jig.glasses.sample.kmp.terrain.PeakPanorama
import jp.jig.glasses.sample.kmp.terrain.SightedPeak
import kotlin.math.hypot
import kotlin.math.roundToInt

/** 画面に置けた山 1 座。座標は**画像の中**（パネルではない）。 */
data class PlacedPeak(val sighted: SightedPeak, val x: Int, val y: Int) {
    val label: String get() = sighted.label
}

/**
 * 視野に入っている山から、グラスに出す名前を選ぶ。
 *
 * **仰角順だけで並べると百名山が押し出される。** 鯖江では日野山（794m・9.6km）が 4.41° で、
 * 白山（2,702m・57.8km）の 2.40° より高く見える —— 近い低山が遠い高峰に勝つ。
 * 名前を知りたいのは白山のほうなので、**百名山に枠を予約する**。
 */
object PeakLabels {

    /**
     * 山名に使う枠。
     *
     * [CANVAS_TEXT_SLOTS] は 8 だが、**1 枠は空けておく**。段階6 の方位合わせは
     * 稜線を出しながら案内を出すので、**案内の文字が山名に押し出されて消えてはいけない**
     * （星しるべ #40 で、先頭の案内ラベルが消える事故を踏んでいる）。
     */
    const val SLOTS = 7

    /**
     * 百名山の予約枠。**47 都道府県庁所在地 × 72 方位 = 3,384 視野で測って決めた値。**
     *
     * 測ったのは「予約枠 N のとき、押し出された山と代わりに入った百名山の仰角差」。
     * 詳細は [docs/team-e/76_terrain-measurements.md]。
     */
    const val FAMOUS_SLOTS = 4

    /**
     * 名前を山頂の何 px 上に置くか。**稜線の線に文字を重ねない。**
     *
     * 枠の高さが [CANVAS_LABEL_HEIGHT]（40px）なので、26px 上げると枠の下辺が
     * 山頂の 6px 上に来る。稜線の線幅は 2〜3px なので、線には掛からない。
     */
    const val LABEL_OFFSET_PX = 26

    /**
     * 視野に入っている山を選んで画面へ置く。
     *
     * 返す並びは**予算を取る順**（[List<Label>.toCanvasElements] が入らないものを落とす）で、
     * 画面上の位置とは関係がない。解説の主役は [nearestToCenter] が別に決める。
     *
     * **[RidgeRenderer.render] と同じ引数で呼ぶこと。** 絵と名前が別々の向きから出ると
     * 星しるべ #37 が山で再発する。呼び口は [RidgeMap.bake] 1 つにまとめてある。
     */
    fun place(
        panorama: PeakPanorama,
        azimuthDeg: Double,
        altitudeDeg: Double,
        rollDeg: Double = 0.0,
        width: Int = RIDGE_WIDTH,
        height: Int = RIDGE_HEIGHT,
        fovDeg: Double = ObservationDefaults.FOV_DEG,
        slots: Int = SLOTS,
        famousSlots: Int = FAMOUS_SLOTS,
    ): List<PlacedPeak> {
        if (slots <= 0) return emptyList()
        val basis = Basis(azimuthDeg, altitudeDeg, rollDeg)
        val k = projectionScale(width, fovDeg)

        val onScreen = ArrayList<PlacedPeak>()
        // 視野の半分より広めに拾う。首を傾けると画面の隅は fov/2 より外まで届く
        for (sighted in panorama.around(azimuthDeg, fovDeg)) {
            // **稜線とまったく同じ式を通す。** [SightedPeak.altitudeDeg] は
            // [Geodesy.apparentDropM] を通った見かけの仰角なので、大気差はもう入っている
            val direction = enu(sighted.azimuthDeg, sighted.altitudeDeg)
            val q = project(direction, basis, k, width, height) ?: continue
            val x = q[0].roundToInt()
            val y = q[1].roundToInt() - LABEL_OFFSET_PX
            if (x < 0 || x > width || y < 0 || y > height) continue
            onScreen += PlacedPeak(sighted, x, y)
        }

        // [PeakPanorama.scan] が仰角の降順に並べてあるので、この時点で既に「高く見える順」。
        // **百名山を先頭へ回す**のがここ
        val reserved = onScreen.indices.filter { onScreen[it].sighted.isFamous }.take(famousSlots).toSet()
        val famous = reserved.map { onScreen[it] }
        val rest = onScreen.filterIndexed { i, _ -> i !in reserved }
        return (famous + rest).take(slots)
    }

    /** 画面に出した山のラベル。**[place] の並びをそのまま予算へ渡す。** */
    fun labels(placed: List<PlacedPeak>): List<Label> = placed.map { Label(it.label, it.x, it.y) }

    /**
     * 視野中心にいちばん近い山。**画面の主役はここから取る。**
     *
     * 距離は**山頂**で測る（[LABEL_OFFSET_PX] を足し戻す）。ラベルの位置で測ると、
     * 上に逃がした分だけ中心判定がずれる。
     */
    fun nearestToCenter(
        placed: List<PlacedPeak>,
        width: Int = RIDGE_WIDTH,
        height: Int = RIDGE_HEIGHT,
    ): PlacedPeak? = placed.minByOrNull {
        hypot(it.x - width / 2.0, it.y + LABEL_OFFSET_PX - height / 2.0)
    }
}
