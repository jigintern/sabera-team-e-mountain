package jp.jig.glasses.sample.kmp.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * 画面の下地。**星しるべの季節の星座に当たるもの。**
 *
 * あちらは実在の星座を描いて名前を出しているが、**こちらは名前を出さない。**
 * 手続きで作った稜線は実在しないので、「◯◯山です」と書けば嘘になる。
 * 本物の稜線は同梱地形から焼くもので、それはグラスとプレビューに出る。
 *
 * 起動ごとに形が変わる（[rememberMountainBackground]）。奥ほど淡く小さく描いて、
 * **遠景を見るアプリだと下地で分かる**ようにする。
 */
class MountainBackground(val seed: Int)

/** 起動ごとに 1 つ選ぶ。**回転で描き直しても形が変わらない**ように保存する */
@Composable
fun rememberMountainBackground(): MountainBackground {
    val seed = rememberSaveable { Random.nextInt(Int.MAX_VALUE) }
    return remember(seed) { MountainBackground(seed) }
}

@Composable
fun MountainBackdrop(
    background: MountainBackground,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.background(SKY)) {
        Canvas(Modifier.fillMaxSize()) {
            // 空。上ほど濃い夜、地平に向かって少し明ける
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color(0xFF0A1420),
                    0.72f to Color(0xFF12212C),
                    1f to Color(0xFF1B2C33),
                ),
            )

            val random = Random(background.seed)
            // 奥から手前へ。**奥ほど淡く・低く・なだらか**
            for (layer in 0 until LAYERS) {
                val depth = layer / (LAYERS - 1f)
                val baseY = size.height * (0.52f + 0.13f * depth)
                val amplitude = size.height * (0.05f + 0.10f * depth)
                val color = Color(
                    red = 0.09f + 0.03f * (1f - depth),
                    green = 0.16f + 0.05f * (1f - depth),
                    blue = 0.19f + 0.06f * (1f - depth),
                    alpha = 1f,
                )
                // 3 つの正弦を重ねて尾根らしい非対称を作る。位相は層ごとに散らす
                val phase = random.nextFloat() * (2 * PI).toFloat()
                val phase2 = random.nextFloat() * (2 * PI).toFloat()
                val phase3 = random.nextFloat() * (2 * PI).toFloat()
                val path = Path().apply {
                    moveTo(0f, size.height)
                    var x = 0f
                    while (x <= size.width) {
                        val t = x / size.width
                        val y = baseY -
                            amplitude * sin(t * 2.1f * PI.toFloat() + phase) -
                            amplitude * 0.55f * sin(t * 5.3f * PI.toFloat() + phase2) -
                            amplitude * 0.28f * sin(t * 11.7f * PI.toFloat() + phase3)
                        lineTo(x, y)
                        x += STEP_PX
                    }
                    lineTo(size.width, size.height)
                    close()
                }
                drawPath(path, color)
            }
        }
    }
}

/** 描く前の下地。Canvas が来る前の 1 フレームで白く光らせないために置く */
private val SKY = Color(0xFF0A1420)

/** 稜線を刻む間隔[px]。細かくしても見た目が変わらないところで止める */
private const val STEP_PX = 6f

/** 重ねる稜線の枚数。奥行きが出て、かつ潰れない枚数 */
private const val LAYERS = 4
