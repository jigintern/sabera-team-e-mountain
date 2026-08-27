package jp.jig.glasses.sample.kmp.ui.component

import android.graphics.Bitmap
import jp.jig.glasses.sample.kmp.glass.PANEL_HEIGHT
import jp.jig.glasses.sample.kmp.glass.PANEL_WIDTH
import jp.jig.glasses.sample.kmp.glass.RidgeMap
import jp.jig.glasses.sample.kmp.glass.toCanvasElements

/**
 * スマホに出すプレビュー 1 枚。
 *
 * **グラスは 1 人しかかけられない。** 同伴者はここで同じ稜線を見るし、
 * かけている本人も方位合わせのときは実景とスマホを見比べる。
 */
class PreviewFrame(
    val bitmap: Bitmap,
    /** 絵の上に重ねる名前。**グラスに出したものと同じ**（[toCanvasElements] の結果から作る） */
    val labels: List<PreviewLabel>,
)

/**
 * プレビューに重ねる名前 1 つ。位置は**絵の中の割合**（0..1）で持つ。
 *
 * 画素で持つと、スマホの画面の大きさが変わるたびに合わなくなる。
 */
class PreviewLabel(val text: String, val fx: Float, val fy: Float)

/**
 * スマホに出すプレビュー。**実機の緑 8 階調に寄せる。**
 *
 * 実機と違う色で出すと「そう見えている」と誤解する。量子化の段数（3bit = 8 段）も
 * グラスに合わせてある。
 *
 * **星図と違って切り詰めない。** 星図は夜空の大半が黒なので光っているところだけ切り出すが、
 * 稜線は**画面を横切る 1 本の線**で、切ると「実景のどこに重なるか」が分からなくなる。
 * パネルの縦横比そのままで出すのが、重ねて見るための正しい見せ方。
 */
fun RidgeMap.toPreviewBitmap(): PreviewFrame {
    val pixels = IntArray(width * height)
    for (index in pixels.indices) {
        val value = gray[index].toInt() and 0xFF
        val level = Math.round(value / 255.0 * 7.0) / 7.0
        pixels[index] = (0xFF shl 24) or
            ((56 * level).toInt() shl 16) or
            ((255 * level).toInt() shl 8) or
            (116 * level).toInt()
    }
    val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    return PreviewFrame(bitmap, previewLabels())
}

/**
 * 絵に重ねる名前を、**グラスへ送るのと同じ並び**から作る。
 *
 * 別に選び直すと、グラスとスマホで違う山名が並ぶ（星しるべ #37 と同じ壊れ方）。
 */
private fun RidgeMap.previewLabels(): List<PreviewLabel> {
    // 名前の位置はパネルの座標。絵は中央に置いてあるので、そのぶんを引いて絵の中へ戻す
    val offsetX = (PANEL_WIDTH - width) / 2
    val offsetY = (PANEL_HEIGHT - height) / 2
    return labels.toCanvasElements(width, height).map { element ->
        PreviewLabel(
            text = element.text,
            fx = ((element.x + element.width / 2 - offsetX) / width.toFloat()).coerceIn(0f, 1f),
            fy = ((element.y + element.height / 2 - offsetY) / height.toFloat()).coerceIn(0f, 1f),
        )
    }
}
