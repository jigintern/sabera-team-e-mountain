package jp.jig.glasses.sample.kmp.tile

import jp.jig.glasses.sample.kmp.terrain.ElevationSource

/**
 * 同梱の標高タイルから標高を引く。
 *
 * **1 回の焼き直しで数百万回呼ばれる**ので、開いたタイルは持ち続ける。
 * 全国 z10 は 707 枚あるが、150km 圏で触るのは数十枚なので全部載っても問題にならない。
 *
 * [bytes] が `null` を返したらそのタイルは存在しない（海）。標高も `null` になる。
 */
class DemElevationSource(
    private val zoom: Int,
    private val bytes: (z: Int, x: Int, y: Int) -> ByteArray?,
) : ElevationSource {

    /** 開いたタイル。`null` は「無いことを確かめ済み」の印で、二度取りに行かない */
    private val cache = HashMap<Long, DemTile?>()

    override fun elevationM(latDeg: Double, lonDeg: Double): Double? {
        val fx = TileGrid.xOf(lonDeg, zoom)
        val fy = TileGrid.yOf(latDeg, zoom)
        val x = TileGrid.floorInt(fx)
        val y = TileGrid.floorInt(fy)
        val tile = tileAt(x, y) ?: return null
        val col = ((fx - x) * tile.size).toInt()
        val row = ((fy - y) * tile.size).toInt()
        return tile.elevationM(col, row)
    }

    private fun tileAt(x: Int, y: Int): DemTile? {
        val key = x.toLong() shl 32 or (y.toLong() and 0xFFFFFFFFL)
        if (cache.containsKey(key)) return cache[key]
        val tile = bytes(zoom, x, y)?.let { runCatching { DemTile.decode(it) }.getOrNull() }
        cache[key] = tile
        return tile
    }
}
