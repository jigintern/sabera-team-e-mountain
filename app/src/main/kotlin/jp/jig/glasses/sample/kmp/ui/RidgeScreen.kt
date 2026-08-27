package jp.jig.glasses.sample.kmp.ui

import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.jigglass.glass.CommandManager
import app.jigglass.glass.GlassClient
import jp.jig.glasses.sample.kmp.alignment.Locator
import jp.jig.glasses.sample.kmp.alignment.YawDriftCorrector
import jp.jig.glasses.sample.kmp.geo.ObservationDefaults
import jp.jig.glasses.sample.kmp.geo.azimuthFromYaw
import jp.jig.glasses.sample.kmp.geo.normalizeDeg
import jp.jig.glasses.sample.kmp.geo.rollFromAccel
import jp.jig.glasses.sample.kmp.glass.PANEL_HEIGHT
import jp.jig.glasses.sample.kmp.glass.PANEL_WIDTH
import jp.jig.glasses.sample.kmp.glass.RIDGE_IMAGE_ID
import jp.jig.glasses.sample.kmp.glass.ROLL_SMOOTHING
import jp.jig.glasses.sample.kmp.glass.RedrawDecider
import jp.jig.glasses.sample.kmp.glass.RidgeMap
import jp.jig.glasses.sample.kmp.glass.SETTLE_MS
import jp.jig.glasses.sample.kmp.glass.batched
import jp.jig.glasses.sample.kmp.glass.clearedCanvasText
import jp.jig.glasses.sample.kmp.glass.lookSeparationDeg
import jp.jig.glasses.sample.kmp.glass.toCanvasElements
import jp.jig.glasses.sample.kmp.terrain.RidgeSession
import jp.jig.glasses.sample.kmp.ui.component.KeepScreenOn
import jp.jig.glasses.sample.kmp.ui.component.LoadingPanel
import jp.jig.glasses.sample.kmp.ui.component.MountainBackdrop
import jp.jig.glasses.sample.kmp.ui.component.MountainBackground
import jp.jig.glasses.sample.kmp.ui.component.PreviewFrame
import jp.jig.glasses.sample.kmp.ui.component.SaberaGreen
import jp.jig.glasses.sample.kmp.ui.component.SaberaSurface
import jp.jig.glasses.sample.kmp.ui.component.SaberaWarning
import jp.jig.glasses.sample.kmp.ui.component.toPreviewBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * 稜線を出している画面。**この間は頭の向き＝見ている方角。**
 *
 * 星しるべの `StarMapScreen` に当たるが、あちらの 3,652 行のうち大半は星表・衛星・解説で、
 * 骨格は同じ 3 つしかない:
 *
 * 1. 6DoF を [YawDriftCorrector] へ通して**方位を作る**
 * 2. [RedrawDecider] が**いつ送るか**を決める（止まってから・6°ずれたら）
 * 3. [RidgeMap.bake] で**絵と名前を 1 回で焼いて**送る
 *
 * **動きに追従させない。** 1 枚 332〜390ms かかるので、首の動きに合わせて送ると
 * 転送のたび画面が消えて点滅になる。
 */
@Composable
fun RidgeScreen(
    client: GlassClient,
    session: RidgeSession,
    background: MountainBackground,
    headingOffsetDeg: Double,
    fovDeg: Double,
    onRecalibrate: () -> Unit,
    onRequestLeave: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val commands = remember(client) { client.createCommandManager() }
    KeepScreenOn()

    val locator = remember(context) { Locator(context) }

    // --- 6DoF から作る視線 ---
    val yawCorrector = remember { YawDriftCorrector() }
    var rawYaw by remember { mutableDoubleStateOf(0.0) }
    var fusedYaw by remember { mutableStateOf<Double?>(null) }
    var pitch by remember { mutableDoubleStateOf(0.0) }
    var roll by remember { mutableDoubleStateOf(0.0) }
    var driftRateDps by remember { mutableDoubleStateOf(0.0) }
    var lastImuAt by remember { mutableStateOf(0L) }

    // --- 焼いたもの。**方位合わせで焼けていれば待たない** ---
    var baking by remember { mutableStateOf(session.profile == null) }
    var bakeNote by remember { mutableStateOf("いまいる場所の地平線を焼いています…") }
    var preview by remember { mutableStateOf<PreviewFrame?>(null) }
    var leading by remember { mutableStateOf<String?>(null) }
    var shownNames by remember { mutableStateOf<List<String>>(emptyList()) }
    var sendNote by remember { mutableStateOf<String?>(null) }
    /** 焼き直した回数。**追従ループを作り直す鍵**（古い地平線を掴んだまま描き続けない） */
    var bakeGeneration by remember { mutableStateOf(0) }

    /** 方位。**まだ 1 サンプルも来ていない間だけ生のヨーで代用する** */
    fun azimuthNow(): Double = azimuthFromYaw(fusedYaw ?: rawYaw, headingOffsetDeg)

    // --- 1. 6DoF を購読して方位を作る ---
    DisposableEffect(commands) {
        commands.startImuData()
        val job: Job = scope.launch {
            commands.imuData.collect { data ->
                rawYaw = data.yawDegrees.toDouble()
                // ピッチは取付補正済みで上向きが負
                pitch = -data.pitchDegrees.toDouble()
                lastImuAt = SystemClock.elapsedRealtime()
                // **首の傾きは重力から起こす**（6DoF はピッチとヨーしか返さない）。
                // 1 サンプルは揺れるので、ゆっくり寄せてから使う
                val measured = rollFromAccel(data.accelXMilliG, data.accelYMilliG, data.accelZMilliG)
                roll += normalizeDeg(measured - roll) * ROLL_SMOOTHING
                val corrected = yawCorrector.update(
                    rawYawDeg = rawYaw,
                    gyroXDps = data.gyroXDps.toDouble(),
                    gyroYDps = data.gyroYDps.toDouble(),
                    gyroZDps = data.gyroZDps.toDouble(),
                    timestampMs = data.timestampMs,
                )
                fusedYaw = corrected.yawDeg
                driftRateDps = corrected.driftRateDps
            }
        }
        onDispose {
            job.cancel()
            // **切断しない限りグラス側は送り続ける。** 画面を出るときに止めないと、
            // 他の画面でも 10Hz のサンプルが BLE を流れ続ける
            runCatching { commands.stopImuData() }
        }
    }

    // --- 2. 観測地を決めて地平線を焼く。**位置が確定したときに 1 回だけ** ---
    LaunchedEffect(Unit) {
        if (session.profile != null) {
            val at = session.bakedAt!!
            val panorama = session.panorama!!
            baking = false
            bakeNote = "標高 ${"%.0f".format(at.elevationM)}m / " +
                "見える山 ${panorama.peaks.size} 座（百名山 ${panorama.famous.size} 座）"
            return@LaunchedEffect
        }
        val started = SystemClock.elapsedRealtime()
        val located = withContext(Dispatchers.IO) { locator.lastKnown() ?: locator.current() }
        val lat = located?.latDeg ?: ObservationDefaults.DEFAULT_LAT_DEG
        val lon = located?.lonDeg ?: ObservationDefaults.DEFAULT_LON_DEG
        // **失敗しても baking を戻す。** 例外で効果が死ぬと、画面には理由が何も出ないまま
        // 待ち表示が残り続ける（段階4で踏んだ）
        val error = runCatching {
            withContext(Dispatchers.Default) { session.bake(lat, lon) }
        }.exceptionOrNull()
        val ms = SystemClock.elapsedRealtime() - started
        baking = false
        bakeNote = when {
            error != null -> "地平線を焼けませんでした（${ms}ms）: ${error.message}"
            else -> {
                val at = session.bakedAt!!
                val panorama = session.panorama!!
                val where = if (located == null) "既定の観測地（測位できず）" else "現在地（${located.provider}）"
                "$where・標高 ${"%.0f".format(at.elevationM)}m / " +
                    "見える山 ${panorama.peaks.size} 座（百名山 ${panorama.famous.size} 座）・${ms}ms"
            }
        }
    }

    // --- 3. 観測地の見張り。**50m 動いたら焼き直す** ---
    // 星は 1km 動いても位置が変わらないが、山は有限距離にあるので見え方そのものが変わる。
    // 50km 先の山が画面 1 画素（0.066°）動く距離が約 57m（ObservationDefaults.REBAKE_DISTANCE_M）
    LaunchedEffect(baking) {
        if (baking) return@LaunchedEffect
        while (true) {
            delay(REBAKE_POLL_MS)
            val located = withContext(Dispatchers.IO) { locator.lastKnown() } ?: continue
            if (!session.needsBake(located.latDeg, located.lonDeg)) continue
            runCatching {
                withContext(Dispatchers.Default) { session.bake(located.latDeg, located.lonDeg) }
            }.onSuccess {
                val at = session.bakedAt!!
                val panorama = session.panorama!!
                bakeNote = "焼き直しました（移動）・標高 ${"%.0f".format(at.elevationM)}m / " +
                    "見える山 ${panorama.peaks.size} 座（百名山 ${panorama.famous.size} 座）"
                // 追従ループを作り直して、**新しい地平線で描き直させる**
                bakeGeneration++
            }.onFailure { sendNote = "焼き直せませんでした: ${it.message}" }
        }
    }

    // --- 4. 追従ループ。**止まってから送る** ---
    LaunchedEffect(baking, headingOffsetDeg, fovDeg, bakeGeneration) {
        if (baking) return@LaunchedEffect
        val profile = session.profile ?: return@LaunchedEffect
        val panorama = session.panorama ?: return@LaunchedEffect
        val decider = RedrawDecider()
        var shownElements = emptyList<CommandManager.CanvasElement>()
        var lastAz: Double? = null
        var lastAlt = 0.0
        var lastRoll = 0.0

        // **前に動いていたアプリの文字が残っている。** 消してから始める
        runCatching { commands.sendCanvasElements(clearedCanvasText()) }

        while (true) {
            val now = SystemClock.elapsedRealtime()
            val az = azimuthNow()
            val alt = pitch
            val settled = decider.settle(now, az, alt)
            val drift = lastAz?.let { lookSeparationDeg(it, lastAlt, az, alt) } ?: Double.MAX_VALUE
            val rolled = abs(normalizeDeg(roll - lastRoll))

            // 1 枚目は drift が MAX_VALUE なので、止まっていれば必ず通る
            if (decider.shouldRedraw(settled, observationChanged = false, driftDeg = drift, rolledDeg = rolled)) {
                val map = withContext(Dispatchers.Default) {
                    RidgeMap.bake(profile, panorama, az, alt, roll, fovDeg = fovDeg)
                }
                if (map.fitsBuffer) {
                    val sent = runCatching {
                        // **画像を先に積み、山名はその後ろに続ける。** 逆にすると
                        // 転送中に名前だけが浮いて見える
                        commands.sendCanvasImage(
                            id = RIDGE_IMAGE_ID,
                            x = (PANEL_WIDTH - map.width) / 2,
                            y = (PANEL_HEIGHT - map.height) / 2,
                            width = map.width,
                            height = map.height,
                            grayscale = map.gray,
                        )
                        val elements = map.labels.toCanvasElements(map.width, map.height)
                        for (batch in elements.batched(shownElements)) commands.sendCanvasElements(batch)
                        elements
                    }
                    sent.onSuccess { elements ->
                        shownElements = elements
                        // **「グラスに出ている絵」を確定するのはここだけ。**
                        // 焼いた時点で進めると、送信に失敗したのに新しい絵の話をすることになる
                        lastAz = az
                        lastAlt = alt
                        lastRoll = roll
                        decider.onDrawn()
                        preview = withContext(Dispatchers.Default) { map.toPreviewBitmap() }
                        leading = map.leading?.label
                        shownNames = map.shownPeaks.map { it.label }
                        sendNote = null
                    }.onFailure { sendNote = "送れませんでした: ${it.message}" }
                } else {
                    sendNote = "バッファ超過 ${map.bufferUsageBytes} バイト。送らない"
                }
                delay(SETTLE_MS)
            }
            delay(FOLLOW_TICK_MS)
        }
    }

    // 画面を出るときにグラスを片づける。**残すと次のアプリの絵に重なる**
    DisposableEffect(commands) {
        onDispose {
            scope.launch(NonCancellable) {
                runCatching {
                    commands.removeCanvasImage(RIDGE_IMAGE_ID)
                    commands.sendCanvasElements(clearedCanvasText())
                }
            }
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        MountainBackdrop(background = background, modifier = Modifier.fillMaxSize())

        Column(
            modifier = Modifier.fillMaxSize().padding(if (landscape) 12.dp else 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
        ) {
            // --- グラスに出ているものと同じ 1 枚 ---
            Box(
                modifier = Modifier.fillMaxWidth().widthIn(max = 460.dp)
                    .aspectRatio(PANEL_WIDTH / PANEL_HEIGHT.toFloat())
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                val frame = preview
                if (frame == null) {
                    LoadingPanel(
                        text = if (baking) "地平線を焼いています" else "稜線を送っています",
                        hint = if (baking) "同梱の地形から作ります（数秒）" else "首を止めると出ます",
                    )
                } else {
                    Image(
                        bitmap = frame.bitmap.asImageBitmap(),
                        contentDescription = "グラスに出ている稜線",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                    // **名前はグラスと同じものを重ねる**（別に選び直さない）
                    frame.labels.forEach { label ->
                        PreviewLabelText(label.text, label.fx, label.fy)
                    }
                }
            }

            // --- いま何が出ているか ---
            Card(
                modifier = Modifier.fillMaxWidth().widthIn(max = 460.dp),
                colors = CardDefaults.cardColors(containerColor = SaberaSurface),
            ) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text(
                        text = leading ?: "山が視野にありません",
                        style = MaterialTheme.typography.headlineSmall,
                        color = if (leading != null) Color.White else Color.White.copy(alpha = 0.6f),
                    )
                    if (shownNames.size > 1) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = shownNames.joinToString("・"),
                            style = MaterialTheme.typography.bodySmall,
                            color = SaberaGreen,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Field("方位", "%.1f°".format(azimuthNow()))
                        Field("仰角", "%.1f°".format(pitch))
                        Field("画角", "%.1f°".format(fovDeg))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = bakeNote,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.62f),
                    )
                    sendNote?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = SaberaWarning)
                    }
                    if (lastImuAt == 0L) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "グラスから首の向きが届いていません",
                            style = MaterialTheme.typography.bodySmall,
                            color = SaberaWarning,
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onRecalibrate) { Text("方位を合わせ直す", color = Color.White) }
                TextButton(
                    onClick = onRequestLeave,
                    colors = ButtonDefaults.textButtonColors(contentColor = SaberaWarning),
                ) { Text("やめる") }
            }
        }
    }
}

/**
 * プレビューに重ねる名前 1 つ。位置は**絵の中の割合**で来る。
 *
 * 割合を画素へ直さず [BiasAlignment] に渡すのは、**文字の幅を知らなくても中央で置ける**から。
 * 画素で置くと、スマホの画面幅が変わるたびに名前が山からずれる。
 */
@Composable
private fun PreviewLabelText(text: String, fx: Float, fy: Float) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = BiasAlignment(fx * 2f - 1f, fy * 2f - 1f),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = SaberaGreen,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun Field(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.5f))
        Text(value, style = MaterialTheme.typography.titleMedium, color = Color.White)
    }
}

/** 追従ループの 1 周。6DoF は 10Hz なので、その半分の間隔で見に行けば取りこぼさない */
private const val FOLLOW_TICK_MS = 50L

/**
 * 観測地を見に行く間隔。
 *
 * **測位そのものは待たない**（`lastKnown` は直近の値をすぐ返す）。歩いて 50m 動くのに
 * 40 秒はかかるので、10 秒ごとに見れば焼き直しが遅れて困ることはない。
 */
private const val REBAKE_POLL_MS = 10_000L
