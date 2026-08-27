package jp.jig.glasses.sample.kmp.tile

import java.io.File
import jp.jig.glasses.sample.kmp.geo.Viewpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **同梱の `data/dem/` そのもの**を読む。tools/build-dem.py が書いたものを
 * アプリ側の実装で読み戻せることを確かめる（形式がずれたらここで落ちる）。
 */
class BundledDemTest {

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "data/dem/index.json").isFile }

    private val source = DemElevationSource(
        zoom = 10,
        bytes = { z, x, y ->
            File(repoRoot, "data/dem/$z/$x/$y.bin").takeIf { it.isFile }?.readBytes()
        },
    )

    @Test
    fun `白山の標高が引ける`() {
        // カタログは 2702m。**124m 画素なので山頂そのものは丸まる**（富士山で 36m 低く出る）
        val h = source.elevationM(36.155063, 136.771449)
        assertNotNull(h)
        assertTrue("白山で $h m は低すぎる", h!! > 2500.0)
        assertTrue("白山で $h m は高すぎる", h < 2750.0)
    }

    @Test
    fun `鯖江の市街地は低い`() {
        val h = source.elevationM(35.9432, 136.1846)
        assertNotNull(h)
        assertTrue("鯖江で $h m は不自然", h!! in -10.0..200.0)
    }

    @Test
    fun `日野山の標高が引ける`() {
        val h = source.elevationM(35.859184, 136.207491)
        assertNotNull(h)
        assertTrue("日野山(794m) で $h m は不自然", h!! in 600.0..800.0)
    }

    @Test
    fun `海の上はタイルが無いので null`() {
        // 日本海の沖合。タイルが存在しない＝地形のデータが無い
        assertNull(source.elevationM(39.0, 134.0))
    }

    @Test
    fun `同梱の index と実ファイルが一致する`() {
        val index = File(repoRoot, "data/dem/index.json").readText()
        val listed = Regex("\"(\\d+)/(\\d+)\"").findAll(index).map { it.groupValues[1] to it.groupValues[2] }.toList()
        assertTrue("index が空", listed.size > 500)
        val missing = listed.filterNot { (x, y) -> File(repoRoot, "data/dem/10/$x/$y.bin").isFile }
        assertEquals("index にあって実体が無いタイル", emptyList<Pair<String, String>>(), missing)
    }

    @Test
    fun `観測地から見て日野山は白山より高く見える`() {
        // 実データで「百名山にラベル枠を予約する理由」を固定する。
        // 仰角だけで 7 枠を並べると、9.6km の日野山(794m)が 57.8km の白山(2702m)を押し出す
        val sabae = Viewpoint(35.9432, 136.1846, source.elevationM(35.9432, 136.1846)!!)
        val hino = sabae.sight(35.859184, 136.207491, 794.0)
        val haku = sabae.sight(36.155063, 136.771449, 2702.0)
        assertTrue("日野山 ${hino.altitudeDeg}° > 白山 ${haku.altitudeDeg}° のはず", hino.altitudeDeg > haku.altitudeDeg)
        assertTrue("どちらも地平線の上にあるはず", hino.visible && haku.visible)
    }
}
