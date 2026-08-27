package jp.jig.glasses.sample.kmp.terrain

import jp.jig.glasses.sample.kmp.catalog.Peak
import jp.jig.glasses.sample.kmp.catalog.PeakCatalog
import jp.jig.glasses.sample.kmp.geo.Geodesy
import jp.jig.glasses.sample.kmp.geo.Sight
import jp.jig.glasses.sample.kmp.geo.Viewpoint
import jp.jig.glasses.sample.kmp.geo.azimuthDeltaDeg
import kotlin.math.abs

/** 観測地から実際に見えている山 1 座と、その見え方。 */
data class SightedPeak(val peak: Peak, val sight: Sight) {
    val azimuthDeg: Double get() = sight.azimuthDeg
    val altitudeDeg: Double get() = sight.altitudeDeg
    val distanceM: Double get() = sight.distanceM
    val isFamous: Boolean get() = peak.isFamous
    val label: String get() = peak.label
}

/**
 * 観測地から見える山ぜんぶ。**[HorizonProfile] と対になる、名前のついた側。**
 *
 * 稜線は「空と大地の境」を描くだけで名前を持たない。どの山がその稜線のどこに立っているかは
 * ここが持つ。**焼き直すのは観測地が変わったときだけ**で、首を振っても作り直さない
 * （見え方は地形で決まり、こちらの向きでは変わらない）。
 */
class PeakPanorama(val peaks: List<SightedPeak>) {

    /** 百名山だけ。**ラベルの予約枠に入れる**（[jp.jig.glasses.sample.kmp.glass.PeakLabels]）。 */
    val famous: List<SightedPeak> by lazy { peaks.filter { it.isFamous } }

    /**
     * **方位が近すぎて取り違える山。** 方位合わせに使ってはいけない側。
     *
     * 鯖江から白山（65.77°）を狙うと、**1.06° 隣に白山釈迦岳・2.10° 隣に七倉山**が居る。
     * 同じ山塊の隣の峰なので、実物を見て「あれが白山だ」と当てるのは難しい。
     * 取り違えたまま合わせると、**方位が 1〜2° ずれた較正が「成功」として残る**
     * ——[jp.jig.glasses.sample.kmp.alignment.RidgeAlignment] の範囲外判定にも掛からない。
     *
     * 鯖江でいちばん安全なのは**日野山**（167.55°・仰角 4.56°・9.6km）。近くて高く、隣に何も無い。
     */
    val confusable: Set<SightedPeak> by lazy {
        peaks.filterTo(HashSet()) { a ->
            peaks.any { b -> a !== b && abs(azimuthDeltaDeg(a.azimuthDeg, b.azimuthDeg)) < CONFUSABLE_DEG }
        }
    }

    /** その山を方位合わせに使ってよいか */
    fun safeToAlignOn(peak: SightedPeak): Boolean = peak !in confusable

    /**
     * 方位 [azimuthDeg] を中心に [halfSpanDeg] 以内に立っている山。
     *
     * **視野に掛かるものだけを毎回の描き直しで扱う**ための粗い絞り込みで、仰角は見ない
     * （視野の上下から外れるかは投影が決める。パネルは横 35° に対し縦が 11° しかないので、
     * ここで円に切ると絵とずれる）。
     */
    fun around(azimuthDeg: Double, halfSpanDeg: Double): List<SightedPeak> =
        peaks.filter { abs(azimuthDeltaDeg(azimuthDeg, it.azimuthDeg)) <= halfSpanDeg }

    companion object {
        /**
         * これより近い方位に別の山があれば取り違える[度]。
         *
         * 画角 35° をパネル 528px に写すと 1° = 15px。**2° 離れていても 30px しかない**ので、
         * 実物を目で見て隣の峰と選り分けるのは無理がある。
         */
        const val CONFUSABLE_DEG = 2.0

        /**
         * 観測地から見える山を洗い出す。**隠れた山は入らない。**
         *
         * [Raycaster.MAX_DISTANCE_M] より遠い山は見ない。地球の丸みで沈むので、
         * 150km では 1,536m ぶん落ち込む。
         *
         * **地平線プロファイルの仰角と突き合わせて絞らない。** 手前の低い尾根の上に、
         * その裏の高い山より低く見えている山があり得るので、山ごとに個別の見通しを引く
         * （[PeakVisibility]）。
         */
        fun scan(
            from: Viewpoint,
            catalog: PeakCatalog,
            elevation: ElevationSource,
        ): PeakPanorama {
            val sighted = ArrayList<SightedPeak>()
            for (peak in catalog.peaks) {
                val distance = Geodesy.distanceM(from.latDeg, from.lonDeg, peak.latDeg, peak.lonDeg)
                if (distance > Raycaster.MAX_DISTANCE_M) continue
                val elevationM = peak.elevationM.toDouble()
                if (!PeakVisibility.isVisible(from, peak.latDeg, peak.lonDeg, elevationM, elevation)) continue
                sighted += SightedPeak(peak, from.sight(peak.latDeg, peak.lonDeg, elevationM))
            }
            // **高く見える順。標高順ではない。** 近い低山が遠い高峰に勝つ
            // （鯖江では日野山 794m が 4.41°、白山 2,702m が 2.40°）。
            // 画面のどこに置くかも、どれを残すかも「実際にどれだけ空を占めるか」で決める
            sighted.sortByDescending { it.altitudeDeg }
            return PeakPanorama(sighted)
        }
    }
}
