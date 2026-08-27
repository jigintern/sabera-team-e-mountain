package jp.jig.glasses.sample.kmp.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * 待っていることの見せ方。
 *
 * **このアプリは黙る場面が多い。** 測位、**見通し計算（実機 2,805ms）**、
 * そして**稜線の転送（332〜390ms。その間グラスは前の絵を消す）**。
 * 文字だけだと「止まったのか、進んでいるのか」が分からないので、回るものを添える。
 *
 * 色はテーマの既定（紫）ではなく SABERA の緑にする。
 */

/**
 * 1 行ぶんの待ち表示。カードや一覧の中に混ぜて使う。
 *
 * @param text いま何を待っているか。「読み込み中」だけでなく**何を**待っているかを書く
 */
@Composable
fun LoadingLine(
    text: String,
    modifier: Modifier = Modifier,
    // 既定は明るい面（星図の画面）に載る色。夜空の画面だけ [SaberaGreen] を渡す
    color: Color = MaterialTheme.colorScheme.primary,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            color = color,
            strokeWidth = 2.dp,
        )
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

/**
 * 絵が出る場所そのものに置く待ち表示。
 *
 * **稜線の画面に入った直後がこれに当たる。** 同梱地形から地平線を焼くのに実機で 2.8 秒、
 * そこから 1 枚目を送り終わるまでさらに 0.4 秒かかる。何も出ないと「壊れた」と思われる。
 *
 * @param hint 待つ以外にしてほしいことがあれば書く（例「首を止めると出ます」）
 */
@Composable
fun LoadingPanel(
    text: String,
    hint: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(28.dp),
            color = SaberaGreen,
            strokeWidth = 3.dp,
        )
        Spacer(Modifier.padding(top = 10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = SaberaGreen)
        if (hint != null) {
            Spacer(Modifier.padding(top = 4.dp))
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
            )
        }
    }
}
