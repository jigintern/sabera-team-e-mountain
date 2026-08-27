package jp.jig.glasses.sample.kmp.glass

import app.jigglass.glass.CommandManager

/** グラスのキャンバス座標系。 */
const val PANEL_WIDTH = 576
const val PANEL_HEIGHT = 360

/**
 * 稜線の標準サイズ。**バッファ上限に余裕を持って収まる**。
 *
 * [RIDGE_MAX_WIDTH] で入らなかったときの落とし先。
 */
const val RIDGE_WIDTH = 528
const val RIDGE_HEIGHT = 330

/**
 * 画像 1 枚の上限いっぱい。16:10 でこれ以上大きくすると必ず弾かれる。
 *
 * `width * height * 2` だけで 369,920 バイトを使うので、圧縮後に残るのは **10,080 バイト**しかない。
 * 背景の下限だけで 5,780 バイト（面積 / 32）なので、稜線と山名で 4,300 バイトを超えると入らない。
 * **送る前に同じ式で数えて、溢れたら [RIDGE_WIDTH] へ落とす。**
 */
const val RIDGE_MAX_WIDTH = 544
const val RIDGE_MAX_HEIGHT = 340

/**
 * 稜線図の縦横比（高さ ÷ 幅）。**画角の縦の広がりはこれで決まる。**
 *
 * パネル（576×360）も稜線の 2 段（544×340・528×330）も同じ 0.625 なので、
 * どのサイズで焼いても視野の形は変わらない。横 35° なら**縦は 11.0°**しかない。
 */
const val RIDGE_ASPECT = RIDGE_MAX_HEIGHT.toDouble() / RIDGE_MAX_WIDTH

const val RIDGE_IMAGE_ID = 0
const val CANVAS_TEXT_SLOTS = 8
const val CANVAS_TEXT_BUDGET_BYTES = 190
const val CANVAS_PACKET_BYTES = 200
const val CANVAS_IMAGE_BUFFER_BYTES = 380_000

/**
 * 文字 1 枠の高さ[px]と、1 文字ぶんの幅[px]。**どちらも見積りで、実測ではない。**
 *
 * ファームが実際に何 px で組むかは分からない。星しるべが実機で分かっているのは
 * 「28px 見当で数えて溢れなかった」ことだけなので、同じ値を踏襲する。
 * ここがずれると重なり除去が甘く／きつくなるだけで、送るバイト数は変わらない。
 */
internal const val LABEL_CHAR_WIDTH = 28
internal const val LABEL_PADDING = 8
const val CANVAS_LABEL_HEIGHT = 40

/**
 * SDK と同じ 3bit RLE で数えた、画像ペイロードのバイト数。
 *
 * **送る前に自分で数える。** 溢れると SDK が黙って弾き、グラスには前の絵が残ったままになる。
 */
fun compressedSizeBytes(gray: ByteArray): Int {
    var bytes = 0
    var i = 0
    while (i < gray.size) {
        val value = gray[i].toInt() and 0xFF shr 5
        var run = 1
        while (i + run < gray.size && run < 32 && (gray[i + run].toInt() and 0xFF shr 5) == value) run++
        bytes++
        i += run
    }
    return bytes
}

/** バッファを何バイト使うか。SDK の数え方（面積 × 2 ＋ 圧縮後）に合わせる。 */
fun canvasBufferUsageBytes(width: Int, height: Int, gray: ByteArray): Int =
    width * height * 2 + compressedSizeBytes(gray)

/**
 * 名前を、**枠の数（8）とバイト数（190）と重なり**で調停する。
 *
 * 190 バイトは 1 電文の上限であると同時に**画面に置ける合計でもある**
 * （星しるべ #40。分けて送ると先に置いたぶんが押し出されて消えた）。
 * 数だけで打ち切っていたころは、日本語の名前 8 個で 200 バイトを超え、
 * 2 電文に割ったところで**先頭の枠から消えていた**。
 *
 * 入らない名前は**飛ばして次を見る**（打ち切らない）。並びは優先順位なので、
 * 余ったバイトに短い名前が入る。
 *
 * **返すのは「実際にグラスへ出るもの」だけ。** [toCanvasElements] と [fittingIndices] が
 * 同じ答えを返すのは、両方がこの 1 本から取っているから —— 絵に出ている山と
 * 名前が食い違うと、星しるべ #37（「オリオン座」と出しながら別の星座を喋る）が山で再発する。
 */
private fun List<Label>.arbitrate(width: Int, height: Int): List<IndexedValue<CommandManager.CanvasElement>> {
    // 画像はパネルの中央に置く。ラベルの座標は画像の中の座標なので、その分だけずらす
    val offsetX = (PANEL_WIDTH - width) / 2
    val offsetY = (PANEL_HEIGHT - height) / 2
    val shown = ArrayList<IndexedValue<CommandManager.CanvasElement>>(CANVAS_TEXT_SLOTS)
    var used = 0
    for ((index, label) in withIndex()) {
        if (shown.size >= CANVAS_TEXT_SLOTS) break
        val elementWidth = (label.text.length * LABEL_CHAR_WIDTH + LABEL_PADDING).coerceAtMost(PANEL_WIDTH)
        val element = CommandManager.CanvasElement(
            id = shown.size,
            x = (offsetX + label.x - elementWidth / 2).coerceIn(0, PANEL_WIDTH - elementWidth),
            y = (offsetY + label.y - CANVAS_LABEL_HEIGHT / 2).coerceIn(0, PANEL_HEIGHT - CANVAS_LABEL_HEIGHT),
            width = elementWidth,
            height = CANVAS_LABEL_HEIGHT,
            text = label.text,
        )
        if (shown.any { it.value overlaps element }) continue
        val bytes = element.byteSize()
        if (used + bytes > CANVAS_TEXT_BUDGET_BYTES) continue
        used += bytes
        shown += IndexedValue(index, element)
    }
    return shown
}

/** グラスへ送る形。並びは枠の id 順（＝優先順）。 */
fun List<Label>.toCanvasElements(width: Int, height: Int): List<CommandManager.CanvasElement> =
    arbitrate(width, height).map { it.value }

/**
 * **実際にグラスへ出た**名前が、元の並びの何番目だったか。
 *
 * 落ちたぶんを知るためではなく、**出た名前に対応する山を引く**ために要る
 * （[RidgeMap.shownPeaks]）。
 */
fun List<Label>.fittingIndices(width: Int, height: Int): List<Int> =
    arbitrate(width, height).map { it.index }

/**
 * 190 バイトに収めて送る形にする。**書き換える前に、消す必要のある枠を消す。**
 *
 * ファームは**新しい矩形しか描き直さない**。同じ id に前より短い名前や左に寄った名前を置くと、
 * **前の名前の末尾が画面に残る**（星しるべの実機で「る」の 1 文字が右上に残った）。
 * 消すのは空文字を送ればよいが、**同じ電文の中で消してから置くと順番が保証されない**ので、
 * 消す分だけを先のバッチにまとめる。
 *
 * 消すのは**必要な枠だけ**にする。毎回 8 枠を空にしてから置き直すと、
 * 動かない名前まで消えて出るのでちらつく。
 */
fun List<CommandManager.CanvasElement>.batched(
    previous: List<CommandManager.CanvasElement>,
): List<List<CommandManager.CanvasElement>> = chunkByBudget(clearsFor(previous)) + chunkByBudget(this)

/**
 * テキスト枠を全部空にする電文。**掃除用。**
 *
 * ファームは**消すまでテキストを持ち続ける**ので、前に動いていたアプリ（や前の版）が
 * 置いた文字が残っていると、あとから送った画像に**重なって出る**（星しるべの実機で踏んだ）。
 * 何が残っているか知りようがないので、**8 枠ぶんまとめて空にする**。
 * 12 バイト × 8 = 96 バイトで 1 電文に収まる。
 */
fun clearedCanvasText(): List<CommandManager.CanvasElement> =
    (0 until CANVAS_TEXT_SLOTS).map { id ->
        CommandManager.CanvasElement(id = id, x = 0, y = 0, width = 0, height = 0, text = "")
    }

/** 上書きでは消え残る枠を、先に空文字で消す */
private fun List<CommandManager.CanvasElement>.clearsFor(
    previous: List<CommandManager.CanvasElement>,
): List<CommandManager.CanvasElement> {
    val next = associateBy { it.id }
    return previous.mapNotNull { old ->
        // 新しい矩形が前の矩形を覆っているなら、そのまま上書きして消え残らない
        if (next[old.id]?.covers(old) == true) return@mapNotNull null
        CommandManager.CanvasElement(id = old.id, x = 0, y = 0, width = 0, height = 0, text = "")
    }
}

/** 190 バイトずつに切る。1 要素だけで超える場合はその 1 つで送る */
private fun chunkByBudget(
    elements: List<CommandManager.CanvasElement>,
): List<List<CommandManager.CanvasElement>> {
    val batches = ArrayList<List<CommandManager.CanvasElement>>()
    var current = ArrayList<CommandManager.CanvasElement>()
    var used = 0
    for (element in elements) {
        val bytes = element.byteSize()
        if (current.isNotEmpty() && used + bytes > CANVAS_TEXT_BUDGET_BYTES) {
            batches += current
            current = ArrayList()
            used = 0
        }
        current += element
        used += bytes
    }
    if (current.isNotEmpty()) batches += current
    return batches
}

/** 電文でこの要素が食うバイト数。座標と寸法で 12 バイト、あとは本文の UTF-8 */
fun CommandManager.CanvasElement.byteSize(): Int = 12 + text.toByteArray(Charsets.UTF_8).size

/** 前の矩形を完全に覆うか。覆っていれば消さずに上書きしてよい */
private infix fun CommandManager.CanvasElement.covers(other: CommandManager.CanvasElement): Boolean =
    x <= other.x && y <= other.y &&
        x + width >= other.x + other.width && y + height >= other.y + other.height

private infix fun CommandManager.CanvasElement.overlaps(other: CommandManager.CanvasElement): Boolean =
    x < other.x + other.width && other.x < x + width && y < other.y + other.height && other.y < y + height
