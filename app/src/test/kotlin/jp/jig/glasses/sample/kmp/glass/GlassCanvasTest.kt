package jp.jig.glasses.sample.kmp.glass

import app.jigglass.glass.CommandManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文字枠の調停。**8 枠・190 バイト・重なり除去**の 3 つを同時に守る。
 *
 * 星しるべが実機で踏んだ 2 件を、ここで数字にして固定する:
 * - 日本語 8 個で 200 バイトを超え、2 電文に割れて**先頭の枠が消えた**（#40）
 * - 前より短い名前で上書きして、**前の名前の末尾が画面に残った**
 */
class GlassCanvasTest {

    private fun labelsOf(count: Int, text: String) =
        (0 until count).map { Label(text, x = 40 + it * 60, y = 40 + it * 40) }

    @Test
    fun `枠は 8 個で打ち切る`() {
        val elements = labelsOf(12, "白山").toCanvasElements(RIDGE_WIDTH, RIDGE_HEIGHT)
        assertEquals(CANVAS_TEXT_SLOTS, elements.size)
        assertEquals("枠の id は 0 から詰める", (0 until CANVAS_TEXT_SLOTS).toList(), elements.map { it.id })
    }

    @Test
    fun `190 バイトを超えたら入らない名前を飛ばして次を見る`() {
        // 「能郷白山」は 12 バイト ＋ 枠 12 バイトで 24 バイト。7 個で 168 バイト使うので、
        // 8 個目（192 バイト）は入らない。**そこで打ち切らず**、15 バイトの「燧」を拾う
        val long = List(8) { Label("能郷白山", x = 200, y = 20 + it * 44) }
        val short = listOf(Label("燧", x = 500, y = 20))
        val elements = (long + short).toCanvasElements(RIDGE_WIDTH, RIDGE_HEIGHT)

        val bytes = elements.sumOf { it.byteSize() }
        assertTrue("$bytes バイト（上限 $CANVAS_TEXT_BUDGET_BYTES）", bytes <= CANVAS_TEXT_BUDGET_BYTES)
        assertEquals("長い名前が 7 個に絞られていない", 7, elements.count { it.text == "能郷白山" })
        assertTrue("余ったバイトに短い名前が入っていない", elements.any { it.text == "燧" })
    }

    @Test
    fun `重なる名前は落ちる`() {
        // 同じ場所に 3 つ置く。1 つだけ残るのが正しい
        val stacked = List(3) { Label("白山", x = 260, y = 160) }
        assertEquals(1, stacked.toCanvasElements(RIDGE_WIDTH, RIDGE_HEIGHT).size)
    }

    @Test
    fun `出た名前の番号が元の並びを指している`() {
        val labels = listOf(
            Label("白山", x = 260, y = 160),
            Label("荒島岳", x = 262, y = 162), // 白山に重なるので落ちる
            Label("日野山", x = 60, y = 300),
        )
        assertEquals(listOf(0, 2), labels.fittingIndices(RIDGE_WIDTH, RIDGE_HEIGHT))
    }

    @Test
    fun `上書きで消え残る枠だけを先に消す`() {
        val previous = listOf(
            element(id = 0, x = 100, y = 100, width = 100, text = "白山"),
            element(id = 1, x = 300, y = 100, width = 100, text = "荒島岳"),
        )
        val next = listOf(
            // 0 番は前より広いので覆える。消さずに上書きしてよい
            element(id = 0, x = 90, y = 100, width = 120, text = "白山"),
            // 1 番は右にずれて前の左端が残る。先に空文字で消す必要がある
            element(id = 1, x = 320, y = 100, width = 100, text = "能郷白山"),
        )
        val batches = next.batched(previous)
        val cleared = batches.first().filter { it.text.isEmpty() }.map { it.id }
        assertEquals("消すのは消え残る枠だけ", listOf(1), cleared)
        assertEquals("消してから置く順になっていない", next, batches.last())
    }

    @Test
    fun `掃除の電文は 8 枠ぶんで 1 電文に収まる`() {
        val cleared = clearedCanvasText()
        assertEquals(CANVAS_TEXT_SLOTS, cleared.size)
        assertTrue("全部空文字でない", cleared.all { it.text.isEmpty() })
        assertTrue("1 電文に収まらない", cleared.sumOf { it.byteSize() } <= CANVAS_TEXT_BUDGET_BYTES)
    }

    private fun element(id: Int, x: Int, y: Int, width: Int, text: String) =
        CommandManager.CanvasElement(id, x, y, width, CANVAS_LABEL_HEIGHT, text)
}
