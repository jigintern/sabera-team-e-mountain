package jp.jig.glasses.sample.kmp.glass

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
