package jp.jig.glasses.sample.kmp.catalog

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 同梱の `data/peaks.json` そのものを読む。**生成物と食い違ったらここで落ちる。** */
class PeakCatalogTest {

    private val catalog: PeakCatalog = PeakCatalog.parse(
        generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "data/peaks.json") }
            .first { it.isFile }
            .readText(),
    )

    @Test
    fun `百名山がちょうど 100 座ある`() {
        assertEquals(100, catalog.famous.size)
        assertEquals(100, catalog.famous.map { it.famousName }.distinct().size)
    }

    @Test
    fun `いちばん高いのは富士山`() {
        val top = catalog.peaks.first()
        assertEquals(3776, top.elevationM)
        assertEquals("富士山", top.label)
    }

    @Test
    fun `百名山は通称をラベルに出す`() {
        // **「赤岳」より「八ヶ岳」のほうが伝わる。** 使う人は登山の初心者
        assertEquals("八ヶ岳", catalog.peaks.first { it.name == "赤岳" }.label)
        assertEquals("白山", catalog.peaks.first { it.name == "白山＜御前峰＞" }.label)
    }

    @Test
    fun `山頂名つきと別名つきは短くする`() {
        // 枠は日本語 5 文字ぶんしかないので、そのままでは入らない
        assertEquals("大船山", catalog.peaks.first { it.name == "くじゅう連山＜大船山＞" }.label)
        assertEquals("利尻山", catalog.peaks.first { it.name == "利尻山（利尻富士）" }.label)
    }

    @Test
    fun `同名の山は標高で別物として入っている`() {
        val komagatake = catalog.peaks.filter { it.name == "駒ヶ岳" }
        assertTrue("駒ヶ岳は 6 座あるはず（実際 ${komagatake.size}）", komagatake.size >= 4)
        assertEquals(komagatake.size, komagatake.map { it.elevationM }.distinct().size)
        // 通称があるものはラベルで区別が付く
        assertEquals("甲斐駒ヶ岳", komagatake.first { it.elevationM == 2967 }.label)
        assertEquals("木曽駒ヶ岳", komagatake.first { it.elevationM == 2956 }.label)
    }

    @Test
    fun `通称でも正式名でも読みでも引ける`() {
        // 「八ヶ岳はどこ？」と聞かれて赤岳を返せないと案内が成立しない
        assertEquals("赤岳", catalog.findByName("八ヶ岳")?.name)
        assertEquals("赤岳", catalog.findByName("赤岳")?.name)
        assertNotNull(catalog.findByName("あらしまだけ"))
        assertEquals(2702, catalog.findByName("白山")?.elevationM)
    }

    @Test
    fun `ラベルはほとんどが 4 文字以内に収まる`() {
        // 枠 1 つは 12 バイト＋文字ぶん。日本語 5 文字で 27 バイトなので 7 枠が限界
        val long = catalog.peaks.count { it.label.length > 5 }
        assertTrue("5 文字超が $long 座。増えすぎならラベルの詰め方を見直す", long < 30)
    }
}
