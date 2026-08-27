package jp.jig.glasses.sample.kmp.geo

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 地上の 2 点の関係を出す。**星と山でいちばん違うのがここ。**
 *
 * 星は無限遠にあるので方向だけ分かれば描ける。山は**有限の距離**にあるので、
 * 距離が分からないと仰角が出ない。しかも遠いほど地球の丸みで沈む。
 *
 * **Android に触らない。** JVM テストで数字を固定できるようにするため。
 */
object Geodesy {

    /** 地球の半径[m]。落ち込みの式と距離の式で同じ値を使う（別々にすると往復が合わなくなる） */
    const val EARTH_RADIUS_M = 6_371_000.0

    /**
     * 大気の屈折を織り込んだ見かけの落ち込み係数。
     *
     * 幾何学だけなら `d² / 2R` だが、大気は光を下へ曲げるので実際の沈みは浅くなる。
     * 測量で慣用の屈折係数 k=0.13 を使うと `(1 - k) = 0.87` が掛かる。
     */
    const val REFRACTION_FACTOR = 0.87

    /**
     * [distanceM] 先の地面が、見かけ上どれだけ下がって見えるか[m]。
     *
     * **距離の 2 乗で効く**ので、遠景では標高そのものより効く。
     * これが見通し計算を半径 150km で打ち切る根拠でもある — 150km で 1,536m 沈むので、
     * それより低い山は標高がいくらあっても地平線の下に隠れる。
     */
    fun apparentDropM(distanceM: Double): Double =
        REFRACTION_FACTOR * distanceM * distanceM / (2.0 * EARTH_RADIUS_M)

    /**
     * [distanceM] 先で、観測者（標高 [observerElevationM]）から見えるようになる最低の標高[m]。
     *
     * 観測者が高いところに立てば、その分だけ下がる。
     */
    fun minimumVisibleElevationM(distanceM: Double, observerElevationM: Double): Double =
        apparentDropM(distanceM) + observerElevationM

    /**
     * 仰角[度]。負なら地平線の下、つまり見えない。
     *
     * 落ち込みを引いてから割る。**この 1 行が「山は互いを隠す」の起点。**
     */
    fun altitudeDeg(distanceM: Double, observerElevationM: Double, targetElevationM: Double): Double {
        if (distanceM <= 0.0) return if (targetElevationM > observerElevationM) 90.0 else -90.0
        val rise = targetElevationM - observerElevationM - apparentDropM(distanceM)
        return Math.toDegrees(atan(rise / distanceM))
    }

    /** 大円距離[m]。半正矢（haversine）で出す。 */
    fun distanceM(lat1Deg: Double, lon1Deg: Double, lat2Deg: Double, lon2Deg: Double): Double {
        val p1 = Math.toRadians(lat1Deg)
        val p2 = Math.toRadians(lat2Deg)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2Deg - lon1Deg)
        val h = sin(dp / 2).let { it * it } + cos(p1) * cos(p2) * sin(dl / 2).let { it * it }
        return 2.0 * EARTH_RADIUS_M * asin(minOf(1.0, sqrt(h)))
    }

    /** 真北を 0°、東回りで測った方位[度]（0..360）。大円の出発方位。 */
    fun azimuthDeg(lat1Deg: Double, lon1Deg: Double, lat2Deg: Double, lon2Deg: Double): Double {
        val p1 = Math.toRadians(lat1Deg)
        val p2 = Math.toRadians(lat2Deg)
        val dl = Math.toRadians(lon2Deg - lon1Deg)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return normalizeAzimuthDeg(Math.toDegrees(atan2(y, x)))
    }

    /**
     * [lat1Deg], [lon1Deg] から方位 [azimuthDeg] へ [distanceM] 進んだ地点の `[緯度, 経度]`。
     *
     * **[azimuthDeg] と [distanceM] の逆演算になっている**必要がある。
     * 見通し計算は 1 方位ぶんのレイを刻んで進むので、ここがずれると稜線ごと傾く。
     */
    fun destination(lat1Deg: Double, lon1Deg: Double, azimuthDeg: Double, distanceM: Double): DoubleArray {
        val p1 = Math.toRadians(lat1Deg)
        val l1 = Math.toRadians(lon1Deg)
        val a = Math.toRadians(azimuthDeg)
        val d = distanceM / EARTH_RADIUS_M
        val sinP2 = sin(p1) * cos(d) + cos(p1) * sin(d) * cos(a)
        val p2 = asin(minOf(1.0, maxOf(-1.0, sinP2)))
        val l2 = l1 + atan2(sin(a) * sin(d) * cos(p1), cos(d) - sin(p1) * sinP2)
        return doubleArrayOf(Math.toDegrees(p2), normalizeLonDeg(Math.toDegrees(l2)))
    }

    /**
     * [destination] と同じものを [out] へ書く版（`out[0]`=緯度, `out[1]`=経度）。
     *
     * **見通し計算は 1 回の焼き直しで数百万点を刻む。** 点ごとに配列を作ると
     * その数だけゴミが出るので、呼ぶ側の使い回しを許す。
     */
    fun destinationInto(
        lat1Deg: Double,
        lon1Deg: Double,
        azimuthDeg: Double,
        distanceM: Double,
        out: DoubleArray,
    ) {
        val p1 = Math.toRadians(lat1Deg)
        val l1 = Math.toRadians(lon1Deg)
        val a = Math.toRadians(azimuthDeg)
        val d = distanceM / EARTH_RADIUS_M
        val sinP2 = sin(p1) * cos(d) + cos(p1) * sin(d) * cos(a)
        val p2 = asin(minOf(1.0, maxOf(-1.0, sinP2)))
        val l2 = l1 + atan2(sin(a) * sin(d) * cos(p1), cos(d) - sin(p1) * sinP2)
        out[0] = Math.toDegrees(p2)
        out[1] = normalizeLonDeg(Math.toDegrees(l2))
    }
}

/** 方位を 0..360 に畳む。 */
fun normalizeAzimuthDeg(deg: Double): Double {
    val d = deg % 360.0
    return if (d < 0.0) d + 360.0 else d
}

/** 経度を -180..180 に畳む。日付変更線を跨いでも破綻させないため。 */
fun normalizeLonDeg(deg: Double): Double {
    var d = (deg + 180.0) % 360.0
    if (d < 0.0) d += 360.0
    return d - 180.0
}

/**
 * [fromDeg] から [toDeg] への方位差[度]。**-180..180 で折り返す。**
 *
 * 生の引き算だと 359.5° と 0.5° の差が -359° になり、首がわずかに動いただけで
 * 描き直しの判定が跳ねる（星しるべが踏んだ）。
 */
fun azimuthDeltaDeg(fromDeg: Double, toDeg: Double): Double {
    var d = (toDeg - fromDeg) % 360.0
    if (d > 180.0) d -= 360.0
    if (d < -180.0) d += 360.0
    return d
}

/** 観測地。稜線の計算はここを起点にする。**50m 動いたら焼き直しが要る。** */
data class Viewpoint(val latDeg: Double, val lonDeg: Double, val elevationM: Double) {

    /** 別の地点をここから見たときの距離・方位・仰角。 */
    fun sight(latDeg: Double, lonDeg: Double, elevationM: Double): Sight {
        val d = Geodesy.distanceM(this.latDeg, this.lonDeg, latDeg, lonDeg)
        return Sight(
            distanceM = d,
            azimuthDeg = Geodesy.azimuthDeg(this.latDeg, this.lonDeg, latDeg, lonDeg),
            altitudeDeg = Geodesy.altitudeDeg(d, this.elevationM, elevationM),
        )
    }

    /** ここから [meters] 以上離れているか。焼き直しの判定に使う。 */
    fun movedMoreThan(other: Viewpoint, meters: Double): Boolean =
        Geodesy.distanceM(latDeg, lonDeg, other.latDeg, other.lonDeg) > meters ||
            abs(elevationM - other.elevationM) > meters
}

/** ある地点を観測地から見たときの見え方。 */
data class Sight(val distanceM: Double, val azimuthDeg: Double, val altitudeDeg: Double) {
    /** 地平線の上にあるか。**負なら描かない。** */
    val visible: Boolean get() = altitudeDeg > 0.0
}
