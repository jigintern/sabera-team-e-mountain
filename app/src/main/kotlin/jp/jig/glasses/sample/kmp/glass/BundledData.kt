package jp.jig.glasses.sample.kmp.glass

import android.content.Context
import jp.jig.glasses.sample.kmp.catalog.PeakCatalog
import jp.jig.glasses.sample.kmp.terrain.ElevationSource
import jp.jig.glasses.sample.kmp.tile.DemElevationSource

/**
 * 同梱データの入口。**プロセスで一度だけ読む。**
 *
 * 実体は `data/`（リポジトリ直下）で、Gradle が assets として畳み込んでいる。
 * 読み口を `(String) -> String` ではなく Context 越しにしているのは、
 * DEM がテキストではなくバイト列だから。
 */
object BundledData {

    @Volatile
    private var catalog: PeakCatalog? = null

    /** 山名カタログ（1059 座）。 */
    fun peaks(context: Context): PeakCatalog =
        catalog ?: synchronized(this) {
            catalog ?: PeakCatalog.parse(
                context.assets.open("peaks.json").use { it.readBytes().decodeToString() },
            ).also { catalog = it }
        }

    /** 同梱の標高。**圏外でもこれだけで動く。** */
    fun elevation(context: Context, zoom: Int = 10): ElevationSource =
        DemElevationSource(zoom) { z, x, y ->
            runCatching { context.assets.open("dem/$z/$x/$y.bin").use { it.readBytes() } }.getOrNull()
        }
}
