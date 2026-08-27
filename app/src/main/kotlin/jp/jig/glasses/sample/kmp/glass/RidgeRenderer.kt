package jp.jig.glasses.sample.kmp.glass

import jp.jig.glasses.sample.kmp.geo.Basis
import jp.jig.glasses.sample.kmp.geo.ObservationDefaults
import jp.jig.glasses.sample.kmp.geo.apparentAltitudeDeg
import jp.jig.glasses.sample.kmp.geo.enu
import jp.jig.glasses.sample.kmp.geo.project
import jp.jig.glasses.sample.kmp.geo.projectionScale
import jp.jig.glasses.sample.kmp.terrain.HorizonProfile
import jp.jig.glasses.sample.kmp.terrain.Raycaster

/**
 * 地平線プロファイルをグラスの 1 枚に焼く。
 *
 * **レンダーループの骨格は星しるべから保存している**:
 *
 * ```
 * プロファイルを alt/az へ → enu(az, alt) → project(v, basis, k, w, h)
 * ```
 *
 * 星しるべの最大の構造的美点は、星・星座線・星座絵・天の川・地平線が**全部この同一チェーンを
 * 通る**こと。稜線・尾根線・山名・案内矢印も同じチェーンを通す。
 *
 * **塗りつぶさない。細線にしない。**（決定#18）
 * 黒＝透明の波導ディスプレイなので、光った画素はそのまま実景の山に重なる。
 * 塗ると本物の山を隠し、細いと屋外の空に負ける。答えは**最上段の階調だけを使い、線幅を稼ぐ**。
 */
object RidgeRenderer {

    /** スカイラインの線幅の上乗せ。実機で見えなかったときはここを上げる */
    const val SKYLINE_EXTRA_RADIUS = 1

    /**
     * 稜線を焼く。
     *
     * @param azimuthDeg いま向いている方位（真北 0°・東回り）
     * @param altimeterDeg いま向いている仰角
     * @param rollDeg 首の傾き
     * @param fovDeg 水平画角。**既定は未実測の仮値**（[ObservationDefaults.FOV_DEG]）
     */
    fun render(
        profile: HorizonProfile,
        azimuthDeg: Double,
        altitudeDeg: Double,
        rollDeg: Double = 0.0,
        width: Int = RIDGE_WIDTH,
        height: Int = RIDGE_HEIGHT,
        fovDeg: Double = ObservationDefaults.FOV_DEG,
    ): ByteArray {
        val gray = ByteArray(width * height)
        val basis = Basis(azimuthDeg, altitudeDeg, rollDeg)
        val k = projectionScale(width, fovDeg)
        val radius = lineRadius(width) + SKYLINE_EXTRA_RADIUS

        // **視野の外まで少し余分に引く。** 端で切ると、傾けたときに画面の隅が空く
        val span = fovDeg * 1.6
        val screen = ArrayList<Pair<Double, Double>>(Math.ceil(span / profile.stepDeg).toInt() + 1)

        var previous: DoubleArray? = null
        for ((az, alt) in profile.segment(azimuthDeg - span / 2.0, span)) {
            // データが無い方位は稜線を切る。**地平線として繋いでしまうと嘘の線が出る**
            if (alt <= Raycaster.NO_DATA_ALTITUDE_DEG) {
                flush(gray, width, height, screen, radius)
                previous = null
                continue
            }
            // **大気差は星より効く。** 稜線は高度 0° 付近に居るので、0° で約 29′ 持ち上がる
            val point = project(enu(az, apparentAltitudeDeg(alt)), basis, k, width, height)
            if (point == null) {
                flush(gray, width, height, screen, radius)
                previous = null
                continue
            }
            // 背後へ回り込んだ点が画面を横切るのを防ぐ（投影は前方半球しか返さないが、
            // 端では隣どうしが大きく飛ぶことがある）
            val prev = previous
            if (prev != null && Math.abs(point[0] - prev[0]) > width / 2.0) {
                flush(gray, width, height, screen, radius)
            }
            screen += point[0] to point[1]
            previous = point
        }
        flush(gray, width, height, screen, radius)
        return gray
    }

    private fun flush(
        gray: ByteArray,
        width: Int,
        height: Int,
        screen: MutableList<Pair<Double, Double>>,
        radius: Int,
    ) {
        if (screen.size >= 2) gray.polyline(width, height, screen, Ink.SKYLINE, radius)
        screen.clear()
    }
}
