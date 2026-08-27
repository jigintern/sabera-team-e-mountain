package jp.jig.glasses.sample.kmp.ui.component

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp

/** 横画面で主要ボタンが画面幅いっぱいの帯にならないための共通上限 */
internal val ACTION_BUTTON_MAX_WIDTH = 320.dp

/** 画面をまたいで見た目を揃える、全幅の主要アクション。 */
@Composable
internal fun CommandButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
    ) {
        Text(label)
    }
}

/**
 * この画面を出している間、スマホを消灯させない。
 *
 * **山を見ている間のスマホは「消えていてよい画面」ではない。** グラスは 1 人しかかけられないので、
 * 同伴者はスマホのプレビューで同じ稜線を見る。方位合わせは実景と見比べながら詰めるので、
 * 30 秒で消える既定のままだと、屋外でロック解除を繰り返すことになる。
 *
 * 消灯を止めるのは観測の流れ（方位合わせと稜線）だけにする。**ホームで点けっぱなしにはしない。**
 */
@Composable
internal fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}
