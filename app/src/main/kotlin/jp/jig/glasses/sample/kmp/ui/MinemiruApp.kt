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
import app.jigglass.glass.CommandManager
import app.jigglass.glass.GlassManager
import jp.jig.glasses.sample.kmp.geo.ObservationDefaults
import jp.jig.glasses.sample.kmp.glass.BundledData
import jp.jig.glasses.sample.kmp.glass.PANEL_HEIGHT
import jp.jig.glasses.sample.kmp.glass.PANEL_WIDTH
import jp.jig.glasses.sample.kmp.glass.RIDGE_IMAGE_ID
import jp.jig.glasses.sample.kmp.glass.RidgeMap
import jp.jig.glasses.sample.kmp.glass.batched
import jp.jig.glasses.sample.kmp.glass.clearedCanvasText
import jp.jig.glasses.sample.kmp.glass.compressedSizeBytes
import jp.jig.glasses.sample.kmp.glass.toCanvasElements
import jp.jig.glasses.sample.kmp.terrain.RidgeSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 段階4〜5の画面。**同梱の地形から本物の稜線と山名を焼いてグラスへ出す。**
 *
 * 方位はスライダーで動かす。段階6でヨーとつなぐまでの仮の入口だが、
 * **実機で「向けた方角の稜線と、そこに立っている山の名前が出る」ことはこれで確かめられる**。
 */
@Composable
fun MinemiruApp(manager: GlassManager) {
    val context = LocalContext.current
    val activity = context as? Activity
    val client by manager.connectedDevice.collectAsState(initial = null)
    val commands = remember(client) { client?.createCommandManager() }
    val scope = rememberCoroutineScope()

    // **山名は地平線と同じ焼き直しに乗せる。** 別々に焼くと新しい稜線に古い名前が乗る
    val session = remember { RidgeSession(BundledData.elevation(context), BundledData.peaks(context)) }
    // グラスに今出ている文字。**消え残りを消すために覚えておく**（前より短い名前で
    // 上書きすると、前の名前の末尾が画面に残る）
    var shownElements by remember { mutableStateOf(emptyList<CommandManager.CanvasElement>()) }
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
            val panorama = session.panorama!!
            "地平線を焼きました（${ms}ms・観測地の標高 ${"%.1f".format(at.elevationM)}m・" +
                "見える山 ${panorama.peaks.size} 座うち百名山 ${panorama.famous.size} 座）"
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
                val panorama = session.panorama ?: return@Button
                scope.launch {
                    // **絵と名前は 1 回の bake から取る。** 別々に呼ぶと向きがずれ得る
                    val map = withContext(Dispatchers.Default) {
                        RidgeMap.bake(profile, panorama, azimuth.toDouble(), altitude.toDouble())
                    }
                    // **送る前に自分で数える。** 溢れると SDK が黙って弾き、前の絵が残る
                    if (!map.fitsBuffer) {
                        status = "バッファ超過 ${map.bufferUsageBytes} バイト。送らない"
                        return@launch
                    }
                    val names = map.shownPeaks.joinToString("・") { it.label }.ifEmpty { "なし" }
                    status = "送っています… ${map.bufferUsageBytes} バイト（圧縮後 ${compressedSizeBytes(map.gray)}）"
                    runCatching {
                        // **画像を先に積み、山名はその後ろに続ける。** 逆にすると、
                        // 転送中に名前だけが浮いて見える（星しるべの実機で確認済み）
                        cm.sendCanvasImage(
                            id = RIDGE_IMAGE_ID,
                            x = (PANEL_WIDTH - map.width) / 2,
                            y = (PANEL_HEIGHT - map.height) / 2,
                            width = map.width,
                            height = map.height,
                            grayscale = map.gray,
                        )
                        val elements = map.labels.toCanvasElements(map.width, map.height)
                        for (batch in elements.batched(shownElements)) cm.sendCanvasElements(batch)
                        shownElements = elements
                    }.onSuccess { status = "稜線と山名を送りました（${map.bufferUsageBytes} バイト）: $names" }
                        .onFailure { status = "失敗: ${it.message}" }
                }
            },
        ) { Text(if (baking) "焼いています…" else "この方角の稜線を送る") }

        Button(
            enabled = commands != null,
            onClick = {
                val cm = commands ?: return@Button
                scope.launch {
                    runCatching {
                        cm.removeCanvasImage(RIDGE_IMAGE_ID)
                        // **文字は画像と別に残る。** 消さないと次の絵に前の山名が重なって出る
                        cm.sendCanvasElements(clearedCanvasText())
                    }
                    shownElements = emptyList()
                    status = "消しました"
                }
            },
        ) { Text("消す") }
    }
}
