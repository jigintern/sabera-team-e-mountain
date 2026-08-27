package jp.jig.glasses.sample.kmp.ui

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.jigglass.glass.GlassManager
import jp.jig.glasses.sample.kmp.geo.ObservationDefaults
import jp.jig.glasses.sample.kmp.glass.BundledData
import jp.jig.glasses.sample.kmp.glass.PANEL_HEIGHT
import jp.jig.glasses.sample.kmp.glass.PANEL_WIDTH
import jp.jig.glasses.sample.kmp.glass.RIDGE_HEIGHT
import jp.jig.glasses.sample.kmp.glass.RIDGE_IMAGE_ID
import jp.jig.glasses.sample.kmp.glass.RIDGE_WIDTH
import jp.jig.glasses.sample.kmp.glass.RidgeRenderer
import jp.jig.glasses.sample.kmp.glass.canvasBufferUsageBytes
import jp.jig.glasses.sample.kmp.glass.compressedSizeBytes
import jp.jig.glasses.sample.kmp.terrain.RidgeSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 段階4の画面。**同梱の地形から本物の稜線を焼いてグラスへ出す。**
 *
 * 方位はスライダーで動かす。段階6でヨーとつなぐまでの仮の入口だが、
 * **実機で「向けた方角の稜線が出る」ことはこれで確かめられる**。
 */
@Composable
fun MinemiruApp(manager: GlassManager) {
    val context = LocalContext.current
    val activity = context as? Activity
    val client by manager.connectedDevice.collectAsState(initial = null)
    val commands = remember(client) { client?.createCommandManager() }
    val scope = rememberCoroutineScope()

    val session = remember { RidgeSession(BundledData.elevation(context)) }
    var azimuth by remember { mutableFloatStateOf(65.77f) }
    var altitude by remember { mutableFloatStateOf(2.0f) }
    var baking by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf("地平線を焼いています…") }

    // **位置が確定したときに 1 回だけ焼く。** 首の動きでは焼き直さない
    LaunchedEffect(Unit) {
        val started = System.nanoTime()
        // **失敗しても baking を戻す。** 例外で LaunchedEffect が死ぬと
        // ボタンが永久にグレーアウトし、画面には理由が何も出ない
        val error = runCatching {
            withContext(Dispatchers.Default) {
                session.bake(ObservationDefaults.DEFAULT_LAT_DEG, ObservationDefaults.DEFAULT_LON_DEG)
            }
        }.exceptionOrNull()
        val ms = (System.nanoTime() - started) / 1_000_000
        baking = false
        status = if (error != null) {
            "地平線を焼けませんでした（${ms}ms）: ${error.message}"
        } else {
            val at = session.bakedAt!!
            "地平線を焼きました（${ms}ms・観測地の標高 ${"%.1f".format(at.elevationM)}m）"
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("峰ミル", style = MaterialTheme.typography.headlineMedium)
        Text(if (commands == null) "グラス未接続" else "グラス接続済み", style = MaterialTheme.typography.bodyLarge)
        Text(status, style = MaterialTheme.typography.bodySmall)

        // **ここが無いとグラスに一度もつながらない。** applicationId を星しるべと分けたので、
        // BLE の紐付け（CompanionDeviceManager の association）は引き継がれない。
        // connectToLastDevice は「前に選んだ端末」がある前提なので、初回は自分で選ばせる
        if (commands == null) {
            Button(
                onClick = {
                    val a = activity ?: return@Button
                    scope.launch {
                        status = "グラスを探しています…"
                        runCatching { manager.showAutomaticSelectionDialog(a) }
                            .onFailure { status = "探せませんでした: ${it.message}" }
                    }
                },
            ) { Text("グラスを探す") }
        }

        Text("方位 ${"%.1f".format(azimuth)}°（65.8° が白山・167.5° が日野山）")
        Slider(
            value = azimuth,
            onValueChange = { azimuth = it },
            valueRange = 0f..360f,
            modifier = Modifier.fillMaxWidth(),
        )
        Text("仰角 ${"%.1f".format(altitude)}°")
        Slider(
            value = altitude,
            onValueChange = { altitude = it },
            valueRange = -10f..30f,
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            enabled = commands != null && !baking,
            onClick = {
                val cm = commands ?: return@Button
                val profile = session.profile ?: return@Button
                scope.launch {
                    val gray = withContext(Dispatchers.Default) {
                        RidgeRenderer.render(profile, azimuth.toDouble(), altitude.toDouble())
                    }
                    val used = canvasBufferUsageBytes(RIDGE_WIDTH, RIDGE_HEIGHT, gray)
                    // **送る前に自分で数える。** 溢れると SDK が黙って弾き、前の絵が残る
                    if (used > jp.jig.glasses.sample.kmp.glass.CANVAS_IMAGE_BUFFER_BYTES) {
                        status = "バッファ超過 $used バイト。送らない"
                        return@launch
                    }
                    status = "送っています… $used バイト（圧縮後 ${compressedSizeBytes(gray)}）"
                    runCatching {
                        cm.sendCanvasImage(
                            id = RIDGE_IMAGE_ID,
                            x = (PANEL_WIDTH - RIDGE_WIDTH) / 2,
                            y = (PANEL_HEIGHT - RIDGE_HEIGHT) / 2,
                            width = RIDGE_WIDTH,
                            height = RIDGE_HEIGHT,
                            grayscale = gray,
                        )
                    }.onSuccess { status = "稜線を送りました（$used バイト）" }
                        .onFailure { status = "失敗: ${it.message}" }
                }
            },
        ) { Text(if (baking) "焼いています…" else "この方角の稜線を送る") }

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
