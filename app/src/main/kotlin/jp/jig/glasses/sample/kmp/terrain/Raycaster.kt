package jp.jig.glasses.sample.kmp.terrain

import jp.jig.glasses.sample.kmp.geo.Geodesy
import jp.jig.glasses.sample.kmp.geo.Viewpoint
import jp.jig.glasses.sample.kmp.geo.normalizeAzimuthDeg

/**
 * 見通し計算。観測地から全方位へ地形をたどり、**各方位でいちばん高く見える点**を求める。
 *
 * **アプリ全体でいちばん重い処理。** 位置が確定したときに 1 回だけ回し、
 * 首の動きでは回さない（[RidgeSession] が焼き直しの要否を持つ）。
 */
object Raycaster {

    /**
     * 見通しを見る上限[m]。**150km で打ち切る根拠は落ち込みの表にある** —
     * 150km で 1,536m 沈むので、それより低い山は標高がいくらあっても隠れる。
     * 200km では 2,732m でほぼ何も残らない。
     */
    const val MAX_DISTANCE_M = 150_000.0

    /**
     * 稜線として信じる下限の距離[m]。
     *
     * **これより近い地面は稜線ではない。** z10 の 1 画素は 124m なので、500m 先では
     * 1 画素が方位 14° ぶんを占める。0.1° 刻みのレイ 140 本が同じ画素を読み、
     * 実地形に無い水平な直線が出る。1km なら 1 画素は 7°、2km なら 3.5° まで下がる。
     *
     * **加えて、ここが「観測者が地面に埋まっている」事故の被害を抑える。**
     * 観測者の標高を GPS から取ると ±20m ずれる。100m 先の地面が 20m 高いと 11° の壁になり、
     * 稜線がまるごと壊れる（実測: 観測者 0m で白山が 11.31° に出た）。
     * 観測地の標高は [jp.jig.glasses.sample.kmp.terrain.ElevationSource] から取るのが正だが、
     * 取り違えても致命傷にならないようにしておく。
     */
    const val MIN_DISTANCE_M = 1_000.0

    /** 方位の刻み[度]。360 / 0.1 = 3,600 本。グラスの 1 画素は 0.066° なので、ほぼ画素刻み */
    const val STEP_DEG = 0.1

    /** データが無い方位に入れる値。**地平線より下**を意味する印で、実在の仰角ではない */
    const val NO_DATA_ALTITUDE_DEG = -10.0

    /**
     * [distanceM] 地点でのサンプル間隔[m]。**近距離は細かく、遠距離は粗く。**
     *
     * 近くの尾根は形が効くので細かく見る必要があるが、遠くは 1 画素に何百 m も
     * 収まるので細かく見ても絵は変わらない。
     */
    fun sampleStepM(distanceM: Double): Double = when {
        distanceM < 10_000.0 -> 100.0
        distanceM < 50_000.0 -> 250.0
        else -> 500.0
    }

    /**
     * 全方位の地平線を焼く。
     *
     * @param maxDistanceM 打ち切り距離。既定は [MAX_DISTANCE_M]
     */
    fun scan(
        from: Viewpoint,
        elevation: ElevationSource,
        maxDistanceM: Double = MAX_DISTANCE_M,
        stepDeg: Double = STEP_DEG,
    ): HorizonProfile {
        val rays = Math.round(360.0 / stepDeg).toInt()
        val altitudes = DoubleArray(rays) { NO_DATA_ALTITUDE_DEG }
        val distances = DoubleArray(rays)
        val elevations = DoubleArray(rays)
        val point = DoubleArray(2)

        for (i in 0 until rays) {
            val az = i * stepDeg
            var best = NO_DATA_ALTITUDE_DEG
            var bestD = 0.0
            var bestH = 0.0
            var d = MIN_DISTANCE_M
            while (d <= maxDistanceM) {
                Geodesy.destinationInto(from.latDeg, from.lonDeg, az, d, point)
                val h = elevation.elevationM(point[0], point[1])
                if (h != null) {
                    val alt = Geodesy.altitudeDeg(d, from.elevationM, h)
                    if (alt > best) {
                        best = alt
                        bestD = d
                        bestH = h
                    }
                }
                d += sampleStepM(d)
            }
            altitudes[i] = best
            distances[i] = bestD
            elevations[i] = bestH
        }
        return HorizonProfile(from, stepDeg, altitudes, distances, elevations)
    }
}

/**
 * 見通し計算の結果。方位ごとの仰角と、そこに立っている地面の距離・標高。
 *
 * **これをグラスに描いた線が「稜線」。** 名前の無い丘も含む、空と大地の境界そのもの。
 */
class HorizonProfile(
    val from: Viewpoint,
    val stepDeg: Double,
    private val altitudes: DoubleArray,
    private val distances: DoubleArray,
    private val elevations: DoubleArray,
) {
    val rayCount: Int get() = altitudes.size

    /** [azimuthDeg] の仰角[度]。方位は一周して折り返す */
    fun altitudeDeg(azimuthDeg: Double): Double = altitudes[indexOf(azimuthDeg)]

    /** [azimuthDeg] でいちばん高く見えた地面までの距離[m] */
    fun distanceM(azimuthDeg: Double): Double = distances[indexOf(azimuthDeg)]

    /** [azimuthDeg] でいちばん高く見えた地面の標高[m] */
    fun elevationM(azimuthDeg: Double): Double = elevations[indexOf(azimuthDeg)]

    /**
     * 稜線を折れ線として渡す。`(方位, 仰角)` の列。
     *
     * [fromAzimuthDeg] から [spanDeg] ぶんを、視野に入るぶんだけ切り出す。
     */
    fun segment(fromAzimuthDeg: Double, spanDeg: Double): List<Pair<Double, Double>> {
        val steps = Math.ceil(spanDeg / stepDeg).toInt()
        return (0..steps).map { k ->
            val az = fromAzimuthDeg + k * stepDeg
            az to altitudeDeg(az)
        }
    }

    private fun indexOf(azimuthDeg: Double): Int {
        val i = Math.round(normalizeAzimuthDeg(azimuthDeg) / stepDeg).toInt()
        return if (i >= altitudes.size) i - altitudes.size else i
    }
}
