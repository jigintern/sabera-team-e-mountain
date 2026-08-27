package jp.jig.glasses.sample.kmp.geo

/**
 * いま見ている向き。**稜線を出している間は「頭の向き＝見ている方角」。**
 *
 * 方位は真北 0°・東回り、仰角は水平 0°。ヨーそのものではない
 * （ヨーは逆に回り、しかも流れる —— [azimuthFromYaw] と
 * [jp.jig.glasses.sample.kmp.alignment.YawDriftCorrector] を通した値がこれ）。
 */
data class Look(val azDeg: Double, val altDeg: Double)

/** 観測の既定値。**測れなかったときの落とし先。** */
object ObservationDefaults {

    /** 測位できないときの観測地 = 鯖江（jig.jp の所在地）。 */
    const val DEFAULT_LAT_DEG = 35.9432
    const val DEFAULT_LON_DEG = 136.1846
    const val DEFAULT_ELEVATION_M = 50.0

    /**
     * グラスの水平画角[度]。**未実測の仮値。**
     *
     * 星しるべが「開いている最大の不明点」に挙げているまま引き継いでいる。
     * **段階6の稜線合わせで実測して埋める** — 既知方位の 2 点が画面上で何画素離れるかから
     * 逆算できる。稜線を実景に重ねるには ±2〜3° が要るので、ここが仮値のままでは詰められない。
     */
    const val FOV_DEG = 35.0

    /** 観測地が変わったとみなす移動量[m]。50km 先の山が画面 1 画素(0.066°)動く距離が約 57m */
    const val REBAKE_DISTANCE_M = 50.0
}
