package jp.jig.glasses.sample.kmp.terrain

/**
 * 標高を引く口。**取り方だけを差し替える。**
 *
 * 同梱ぶんは assets、テストは作り物か手元のファイル、上乗せは通信。
 * **`null` は「データが無い」**で、`0.0`（標高 0m）とは別物 —
 * 海のタイルはそもそも存在しないので、遮蔽の判定では「遮らない」側に倒す。
 */
fun interface ElevationSource {
    fun elevationM(latDeg: Double, lonDeg: Double): Double?
}
