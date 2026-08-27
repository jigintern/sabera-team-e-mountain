package jp.jig.glasses.sample.kmp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.jigglass.glass.GlassManager
import jp.jig.glasses.sample.kmp.glass.Ink
import jp.jig.glasses.sample.kmp.glass.PANEL_HEIGHT
import jp.jig.glasses.sample.kmp.glass.PANEL_WIDTH
import jp.jig.glasses.sample.kmp.glass.RIDGE_HEIGHT
import jp.jig.glasses.sample.kmp.glass.RIDGE_IMAGE_ID
import jp.jig.glasses.sample.kmp.glass.RIDGE_WIDTH
import jp.jig.glasses.sample.kmp.glass.canvasBufferUsageBytes
import jp.jig.glasses.sample.kmp.glass.compressedSizeBytes
import jp.jig.glasses.sample.kmp.glass.lineRadius
import jp.jig.glasses.sample.kmp.glass.polyline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.sin

/**
 * 段階2の画面。**グラスに画像が 1 枚出ることだけを確かめる。**
 *
 * 本物の稜線（段階4）に置き換わるまでの足場だが、送る絵は
 * **太い明るいストローク**（決定#18）にしてある。屋外での視認性は実測データが
 * 無いので、ここで先に見ておけるようにするため。
 */
@Composable
fun MinemiruApp(manager: GlassManager) {
    val client by manager.connectedDevice.collectAsState(initial = null)
    val commands = remember(client) { client?.createCommandManager() }
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("グラスをつないでください") }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("峰ミル", style = MaterialTheme.typography.headlineMedium)
        Text(
            if (commands == null) "未接続" else "接続済み",
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(status, style = MaterialTheme.typography.bodyMedium)

        Button(
            enabled = commands != null,
            onClick = {
                val cm = commands ?: return@Button
                scope.launch {
                    status = "描いています…"
                    val gray = withContext(Dispatchers.Default) { proofRidge(RIDGE_WIDTH, RIDGE_HEIGHT) }
                    val used = canvasBufferUsageBytes(RIDGE_WIDTH, RIDGE_HEIGHT, gray)
                    status = "送っています… ${used} バイト（圧縮後 ${compressedSizeBytes(gray)}）"
                    runCatching {
                        cm.sendCanvasImage(
                            id = RIDGE_IMAGE_ID,
                            x = (PANEL_WIDTH - RIDGE_WIDTH) / 2,
                            y = (PANEL_HEIGHT - RIDGE_HEIGHT) / 2,
                            width = RIDGE_WIDTH,
                            height = RIDGE_HEIGHT,
                            grayscale = gray,
                        )
                    }.onSuccess {
                        status = "送りました（$used バイト）"
                    }.onFailure {
                        status = "失敗: ${it.message}"
                    }
                }
            },
        ) { Text("稜線もどきを 1 枚送る") }

        Button(
            enabled = commands != null,
            onClick = {
                val cm = commands ?: return@Button
                scope.launch {
                    runCatching { cm.removeCanvasImage(RIDGE_IMAGE_ID) }
                    status = "消しました"
                }
            },
        ) { Text("消す") }
    }
}

/**
 * 段階2の検証用の絵。**本物の地形ではない。**
 *
 * 正弦を重ねただけの折れ線を、太く明るいストロークで描く。
 * 見たいのは 2 つだけ — ①画像が出るか ②屋外で線が見えるか。
 */
private fun proofRidge(width: Int, height: Int): ByteArray {
    val gray = ByteArray(width * height)
    val radius = lineRadius(width) + 1
    val points = (0 until width step 4).map { x ->
        val t = x.toDouble() / width
        val y = height * 0.55 -
            height * 0.16 * sin(t * 6.3) -
            height * 0.07 * sin(t * 17.0) -
            height * 0.03 * sin(t * 41.0)
        x.toDouble() to y
    }
    gray.polyline(width, height, points, Ink.SKYLINE, radius)
    return gray
}
