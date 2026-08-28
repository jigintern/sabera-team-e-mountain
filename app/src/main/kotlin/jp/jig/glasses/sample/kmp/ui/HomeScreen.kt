package jp.jig.glasses.sample.kmp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import jp.jig.glasses.sample.kmp.catalog.Attribution
import jp.jig.glasses.sample.kmp.ui.component.ACTION_BUTTON_MAX_WIDTH
import jp.jig.glasses.sample.kmp.ui.component.MountainBackdrop
import jp.jig.glasses.sample.kmp.ui.component.MountainBackground
import jp.jig.glasses.sample.kmp.ui.component.SaberaGreen
import jp.jig.glasses.sample.kmp.ui.component.SaberaOnAccent

/** ホームに出すひとこと。**中身は同梱データから作る**ので圏外でも出る */
data class MountainTip(val header: String, val text: String)

/**
 * 入口の画面。
 *
 * **ひとことをここに出す**（[tip]）。グラスの挨拶に出しているものと同じで、起動ごとに変わる。
 * スマホにも出すのは、**グラスは 1 人しかかけられない**から —— 同伴者はここを見る。
 */
@Composable
fun HomeScreen(
    background: MountainBackground,
    tip: MountainTip?,
    onStart: () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        MountainBackdrop(background = background, modifier = Modifier.fillMaxSize())

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp, vertical = if (landscape) 8.dp else 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(
                if (landscape) 6.dp else 16.dp,
                Alignment.CenterVertically,
            ),
        ) {
            // 星しるべはロゴ画像を持っているが、こちらは字で出す。
            // **書体は共通**（M PLUS Rounded）なので、並べても同じアプリ族に見える
            Text(
                text = "峰ミル",
                style = if (landscape) {
                    MaterialTheme.typography.displaySmall
                } else {
                    MaterialTheme.typography.displayMedium
                },
                color = Color.White,
            )
            Text(
                text = "山の名前を、かけたまま。",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White.copy(alpha = 0.82f),
                textAlign = TextAlign.Center,
            )

            // **ひとことはスタートの手前に置く。** 押したあとの画面では読まれない。
            // **できる前から高さだけ空けておく**（あとから足すと、押そうとした先が動く）
            Box(
                modifier = Modifier.fillMaxWidth().widthIn(max = 420.dp)
                    .height(if (landscape) TIP_RESERVE_LANDSCAPE else TIP_RESERVE),
                contentAlignment = Alignment.Center,
            ) {
                if (tip != null) TipCard(tip)
            }

            Button(
                onClick = onStart,
                modifier = Modifier.widthIn(max = ACTION_BUTTON_MAX_WIDTH).fillMaxWidth()
                    .height(if (landscape) 44.dp else 52.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SaberaGreen,
                    contentColor = SaberaOnAccent,
                ),
            ) {
                Text("スタート")
            }

            Text(
                text = "全国の地形を同梱しています。圏外でも動きます",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
            )

            // **出典はアプリの中に出す義務がある。** 同梱している山名も標高も国土地理院の
            // コンテンツで、規約が出典の明示（標高タイルは加工した旨まで）を求めている。
            // 文言は [Attribution] 1 か所から取る —— 2 か所に書くと必ず片方が古くなる
            Text(
                text = Attribution.ONE_LINE,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.45f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** ひとことのために空けておく高さ。**できるまでは空のまま置く** */
private val TIP_RESERVE = 84.dp
private val TIP_RESERVE_LANDSCAPE = 60.dp

/** ひとこと 1 枚。**背景の稜線に負けないよう、薄い板を敷いてから字を置く** */
@Composable
private fun TipCard(tip: MountainTip, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black.copy(alpha = 0.42f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(tip.header, style = MaterialTheme.typography.labelSmall, color = SaberaGreen)
        Spacer(Modifier.height(3.dp))
        Text(
            tip.text,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.9f),
        )
    }
}
