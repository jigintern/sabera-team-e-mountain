package jp.jig.glasses.sample.kmp.tile

import jp.jig.glasses.sample.kmp.terrain.ElevationSource
import kotlin.math.floor

/**
 * 同梱の標高タイルから標高を引く。
 *
 * **1 回の焼き直しで数百万回呼ばれる**ので、開いたタイルは持ち続ける。
 * 全国 z10 は 707 枚あるが、150km 圏で触るのは数十枚なので全部載っても問題にならない。
 *
 * **画素は双線形で混ぜる。** 最近傍だと 1 画素（z10 で 124m）の中では値が変わらず、
 * 近距離で稜線が階段になる — 500m 先では 124m 画素 1 つが方位 14° ぶんを占めるので、
 * 0.1° 刻みのレイ 140 本が同じ値を返し、**実地形に無い水平な直線**が出る（実測して踏んだ）。
 *
 * [bytes] が `null` を返したらそのタイルは存在しない（海）。標高も `null` になる。
 */
class DemElevationSource(
    private val zoom: Int,
    private val bytes: (z: Int, x: Int, y: Int) -> ByteArray?,
) : ElevationSource {

    /** 開いたタイル。`null` は「無いことを確かめ済み」の印で、二度取りに行かない */
    private val cache = HashMap<Long, DemTile?>()

    /** 画素の辺の数。最初のタイルを開いたときに確定する（形式に入っている） */
    private var tileSize = 256

    override fun elevationM(latDeg: Double, lonDeg: Double): Double? {
        // このズームでの通し画素座標。画素の中心が整数＋0.5 に来るようずらす
        val gx = TileGrid.xOf(lonDeg, zoom) * tileSize - 0.5
        val gy = TileGrid.yOf(latDeg, zoom) * tileSize - 0.5
        val x0 = floor(gx).toInt()
        val y0 = floor(gy).toInt()
        val tx = gx - x0
        val ty = gy - y0

        val h00 = pixel(x0, y0) ?: return null
        // 隣が海（タイルが無い）なら混ぜずにその画素を返す。**null を 0m として混ぜない** —
        // 海岸線の内側が引きずり下げられて、遮蔽判定が「見える」側に倒れる
        val h10 = pixel(x0 + 1, y0) ?: h00
        val h01 = pixel(x0, y0 + 1) ?: h00
        val h11 = pixel(x0 + 1, y0 + 1) ?: h10
        val top = h00 + (h10 - h00) * tx
        val bottom = h01 + (h11 - h01) * tx
        return top + (bottom - top) * ty
    }

    /** 通し画素座標 ([gx], [gy]) の標高。タイルを跨いでも引ける */
    private fun pixel(gx: Int, gy: Int): Double? {
        val size = tileSize
        val x = Math.floorDiv(gx, size)
        val y = Math.floorDiv(gy, size)
        val tile = tileAt(x, y) ?: return null
        return tile.elevationM(Math.floorMod(gx, size), Math.floorMod(gy, size))
    }

    private fun tileAt(x: Int, y: Int): DemTile? {
        val key = x.toLong() shl 32 or (y.toLong() and 0xFFFFFFFFL)
        if (cache.containsKey(key)) return cache[key]
        val tile = bytes(zoom, x, y)?.let { runCatching { DemTile.decode(it) }.getOrNull() }
        if (tile != null) tileSize = tile.size
        cache[key] = tile
        return tile
    }
}
