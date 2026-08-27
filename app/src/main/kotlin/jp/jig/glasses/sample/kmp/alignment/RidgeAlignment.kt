package jp.jig.glasses.sample.kmp.alignment

import jp.jig.glasses.sample.kmp.geo.Basis
import jp.jig.glasses.sample.kmp.geo.DEG
import jp.jig.glasses.sample.kmp.geo.azimuthFromYaw
import jp.jig.glasses.sample.kmp.geo.Geodesy
import jp.jig.glasses.sample.kmp.geo.enu
import jp.jig.glasses.sample.kmp.geo.headingOffsetFor
import jp.jig.glasses.sample.kmp.geo.normalizeDeg
import jp.jig.glasses.sample.kmp.geo.project
import kotlin.math.abs
import kotlin.math.atan

/**
 * **稜線合わせ。星ではできない、山だけの較正。**
 *
 * 星は点で、どれがどれか分からない。**山の稜線は形が一意**なので
 * 「描いた線を実物に重ねてください」が成立する。これが方位合わせの 2 段目で、
 * スマホの十字合わせ（[CalibrationEstimator]・±10°）を ±1° まで追い込む。
 *
 * **副産物として画角が実測できる。** [jp.jig.glasses.sample.kmp.geo.ObservationDefaults.FOV_DEG]
 * = 35.0 は星しるべから引き継いだ**未実測の仮値**で、稜線を実景に重ねるには ±2〜3° が要る
 * のに、画角が違えば画面の端は必ずずれる。
 *
 * ## 合わせ方（**確定はボタンではなく静止で取る**。押す動作そのものが精度を壊す）
 *
 * | | やること | 分かるもの |
 * |---|---|---|
 * | 1 | 画面**中央**の照準に、実物の峰を入れて静止 | 方位オフセット |
 * | 2 | 画面**右寄り**の印に、別の峰を入れて静止 | **画角** |
 *
 * 2 の理屈 —— 1 で視野の中心がどの方位かは分かっている。2 で「真方位の分かっている峰が
 * 画面のこの x に来た」が分かるので、**その 1 点を通す倍率**が決まり、画角が逆算できる。
 * 使う人には「印に山を入れて止める」しか要求しない。
 */
object RidgeAlignment {

    /** 2 つ目の印を画面のどこに置くか（幅に対する割合）。**中心から遠いほど画角がよく効く** */
    const val EDGE_MARK_RATIO = 0.75

    /**
     * 画角として受け付ける範囲[度]。
     *
     * **外れた値は捨てる。** 山を取り違えて止めると桁違いの答えが出るので、
     * 較正が壊れるより「合わなかった」と言うほうがよい。
     */
    const val MIN_FOV_DEG = 10.0
    const val MAX_FOV_DEG = 80.0

    /** 印に峰を入れて静止した 1 回ぶん。 */
    data class Hold(
        /** 入れた峰の真方位[度]。カタログから引く */
        val peakAzimuthDeg: Double,
        /**
         * 同じ峰の仰角[度]。**[Geodesy.altitudeDeg] が返す見かけの仰角をそのまま渡す。**
         * 大気差は落ち込みの係数 k=0.13 に入っているので、ここで足さない。
         */
        val peakAltitudeDeg: Double,
        /** そのときのヨー[度]。[YawDriftCorrector] を通したもの */
        val yawDeg: Double,
        /** そのときの仰角[度]（ピッチ） */
        val pitchDeg: Double,
        /** そのときの首の傾き[度] */
        val rollDeg: Double = 0.0,
        /** 峰を入れた印の画面 x[px] */
        val markX: Double,
    )

    /**
     * 中央の照準に入れた峰から、方位オフセットを出す。
     *
     * 画面中央 = 視線そのものなので、**その峰の真方位が視線の方位**。
     * ヨーは逆に回るので [headingOffsetFor] で符号を畳む。
     */
    fun headingOffsetFrom(hold: Hold): Double = headingOffsetFor(hold.peakAzimuthDeg, hold.yawDeg)

    /**
     * 端の印に入れた峰から、画角[度]を出す。範囲外なら `null`。
     *
     * **投影の式をそのまま逆に解く。** 倍率 1 で投影した x が中心から何画素かを見て、
     * 実際に何画素のところへ入れられたかとの比が倍率 k になる。
     * 仰角も首の傾きも [Basis] と [project] が織り込むので、水平面で近似していない。
     */
    fun fovDegFrom(hold: Hold, headingOffsetDeg: Double, width: Int, height: Int): Double? {
        val azimuth = azimuthFromYaw(hold.yawDeg, headingOffsetDeg)
        val basis = Basis(azimuth, hold.pitchDeg, hold.rollDeg)
        // **稜線と同じ式を通す。** 較正と絵で仰角の扱いが違うと、合わせたつもりでずれる
        val direction = enu(hold.peakAzimuthDeg, hold.peakAltitudeDeg)
        val unit = project(direction, basis, 1.0, width, height) ?: return null

        val unitOffset = unit[0] - width / 2.0
        val markOffset = hold.markX - width / 2.0
        // 中心に入れられたら画角は決まらない（0 で割る）。印と峰が同じ側に無いのも取り違え
        if (abs(unitOffset) < 1e-9 || abs(markOffset) < 1.0) return null
        val k = markOffset / unitOffset
        if (k <= 0.0) return null

        return fovDegForScale(k, width).takeIf { it in MIN_FOV_DEG..MAX_FOV_DEG }
    }

    /**
     * 倍率 [k] から画角[度]を戻す。
     * [jp.jig.glasses.sample.kmp.geo.projectionScale] の逆で、往復が閉じることをテストで固定してある。
     */
    fun fovDegForScale(k: Double, width: Int): Double = 4.0 * atan(width / (4.0 * k)) * DEG

    /**
     * 2 回の静止から較正をまとめる。**片方だけでも取れたぶんは返す。**
     *
     * @param center 中央の照準に入れた静止。無ければ方位は [fallbackHeadingOffsetDeg] のまま
     * @param edge 端の印に入れた静止。無ければ画角は [fallbackFovDeg] のまま
     */
    fun estimate(
        center: Hold?,
        edge: Hold?,
        width: Int,
        height: Int,
        fallbackHeadingOffsetDeg: Double,
        fallbackFovDeg: Double,
    ): Result {
        val heading = center?.let { headingOffsetFrom(it) } ?: fallbackHeadingOffsetDeg
        val fov = edge?.let { fovDegFrom(it, heading, width, height) }
        return Result(
            headingOffsetDeg = heading,
            fovDeg = fov ?: fallbackFovDeg,
            headingMeasured = center != null,
            fovMeasured = fov != null,
            // 端の静止が来たのに画角が出せなかった = 取り違えか、印に入っていない
            edgeRejected = edge != null && fov == null,
        )
    }

    /**
     * 較正の結果。**何を実測できて何が仮値のままかを持ち歩く** —
     * 「合わせた」と「合わせたつもり」を画面で区別できないと、次の人が実測値だと信じる。
     */
    data class Result(
        val headingOffsetDeg: Double,
        val fovDeg: Double,
        val headingMeasured: Boolean,
        val fovMeasured: Boolean,
        val edgeRejected: Boolean,
    )

    /**
     * 較正がどれだけずれているかの目安[度]。**中央の峰が画面上で何度ずれて見えるか。**
     *
     * 合わせ直しが要るかを画面に出すために使う。稜線を実景に重ねるには ±2〜3° が要る。
     */
    fun residualDeg(hold: Hold, headingOffsetDeg: Double): Double =
        abs(normalizeDeg(hold.peakAzimuthDeg - azimuthFromYaw(hold.yawDeg, headingOffsetDeg)))
}
