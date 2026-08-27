package jp.jig.glasses.sample.kmp.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.jigglass.glass.GlassClient
import jp.jig.glasses.sample.kmp.alignment.CalibrationEstimator
import jp.jig.glasses.sample.kmp.alignment.CalibrationMarker
import jp.jig.glasses.sample.kmp.alignment.Compass
import jp.jig.glasses.sample.kmp.alignment.CompassGate
import jp.jig.glasses.sample.kmp.alignment.HoldFeedback
import jp.jig.glasses.sample.kmp.alignment.Locator
import jp.jig.glasses.sample.kmp.alignment.RidgeAlignment
import jp.jig.glasses.sample.kmp.alignment.YawDriftCorrector
import jp.jig.glasses.sample.kmp.geo.ObservationDefaults
import jp.jig.glasses.sample.kmp.geo.azimuthFromYaw
import jp.jig.glasses.sample.kmp.geo.normalizeDeg
import jp.jig.glasses.sample.kmp.geo.rollFromAccel
import jp.jig.glasses.sample.kmp.glass.PANEL_HEIGHT
import jp.jig.glasses.sample.kmp.glass.PANEL_WIDTH
import jp.jig.glasses.sample.kmp.glass.RIDGE_HEIGHT
import jp.jig.glasses.sample.kmp.glass.RIDGE_IMAGE_ID
import jp.jig.glasses.sample.kmp.glass.RIDGE_WIDTH
import jp.jig.glasses.sample.kmp.glass.ROLL_SMOOTHING
import jp.jig.glasses.sample.kmp.glass.RidgeMap
import jp.jig.glasses.sample.kmp.glass.clearedCanvasText
import jp.jig.glasses.sample.kmp.glass.sightMark
import jp.jig.glasses.sample.kmp.terrain.PeakPanorama
import jp.jig.glasses.sample.kmp.terrain.RidgeSession
import jp.jig.glasses.sample.kmp.terrain.SightedPeak
import jp.jig.glasses.sample.kmp.ui.component.KeepScreenOn
import jp.jig.glasses.sample.kmp.ui.component.LoadingPanel
import jp.jig.glasses.sample.kmp.ui.component.MountainBackdrop
import jp.jig.glasses.sample.kmp.ui.component.MountainBackground
import jp.jig.glasses.sample.kmp.ui.component.SaberaGreen
import jp.jig.glasses.sample.kmp.ui.component.SaberaOnAccent
import jp.jig.glasses.sample.kmp.ui.component.SaberaSurface
import jp.jig.glasses.sample.kmp.ui.component.SaberaWarning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * 方位合わせ。**2 段構え。**
 *
 * | 段 | やること | 届く精度 |
 * |---|---|---|
 * | 1 粗合わせ | スマホを顔の前にかざして十字を重ね、**静止で確定** | ±5〜15°（地磁気の誤差） |
 * | 2 稜線合わせ | 選んだ峰を画面の印に入れて**静止で確定** | ±1°・**画角も実測できる** |
 *
 * **星ではこれができない。** 星は点で、どれがどれか分からない。
 * **山の稜線は形が一意**なので「この山を印に入れてください」が成立する。
 *
 * **確定はボタンではなく静止で取る。** 押す動作そのものが精度を壊す（星しるべの作法）。
 */
@Composable
fun CalibrationScreen(
    client: GlassClient,
    session: RidgeSession,
    background: MountainBackground,
    headingOffsetDeg: Double,
    fovDeg: Double,
    /**
     * 合わせを抜けるときに必ず 1 回だけ呼ぶ。**途中で抜けても、そこまで測れたぶんを渡す。**
     *
     * 粗合わせ（15 秒）を終えたあとに山を選べないことは十分あるので、
     * **そこで抜けたときに粗合わせの結果を捨ててはいけない** —— 捨てると
     * オフセット 0 のまま稜線が出て、まるで違う方角の山が並ぶ。
     */
    onCalibrated: (RidgeAlignment.Result) -> Unit,
    onHome: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val commands = remember(client) { client.createCommandManager() }
    val compass = remember(context) { Compass(context) }
    val feedback = remember(context) { HoldFeedback(context) }
    val locator = remember(context) { Locator(context) }
    val estimator = remember { CalibrationEstimator() }
    val yawCorrector = remember { YawDriftCorrector() }
    KeepScreenOn()

    var step by remember { mutableStateOf(Step.COARSE) }
    var rawYaw by remember { mutableDoubleStateOf(0.0) }
    var fusedYaw by remember { mutableStateOf<Double?>(null) }
    var pitch by remember { mutableDoubleStateOf(0.0) }
    var roll by remember { mutableDoubleStateOf(0.0) }
    var imuSeen by remember { mutableStateOf(false) }

    var offset by remember { mutableDoubleStateOf(headingOffsetDeg) }
    var fov by remember { mutableDoubleStateOf(fovDeg) }
    var fovMeasured by remember { mutableStateOf(false) }
    /** 粗合わせ（スマホの十字）が決まったか。**±5〜15° までしか詰まらない** */
    var coarseDone by remember { mutableStateOf(false) }

    /**
     * 方位を**稜線合わせ**で取ったか（±1°）。
     *
     * 粗合わせと同じ旗にしてはいけない —— [RidgeAlignment.Result.headingMeasured] は
     * 「稜線合わせで取った」の意味で使う側（稜線の画面）が読むので、
     * 粗合わせで true にすると**粗いままなのに「合わせた」と表示される**。
     */
    var headingAligned by remember { mutableStateOf(false) }

    var gate by remember { mutableStateOf(CompassGate()) }
    var accuracyText by remember { mutableStateOf("") }
    var distortion by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf(0f) }
    var note by remember { mutableStateOf<String?>(null) }

    var target by remember { mutableStateOf<SightedPeak?>(null) }
    var centerPeak by remember { mutableStateOf<SightedPeak?>(null) }
    var baking by remember { mutableStateOf(session.panorama == null) }

    fun azimuthNow(): Double = azimuthFromYaw(fusedYaw ?: rawYaw, offset)

    // --- 6DoF。**粗合わせでも稜線合わせでも同じ口から取る** ---
    DisposableEffect(commands) {
        commands.startImuData()
        val job: Job = scope.launch {
            commands.imuData.collect { data ->
                rawYaw = data.yawDegrees.toDouble()
                pitch = -data.pitchDegrees.toDouble()
                imuSeen = true
                val measured = rollFromAccel(data.accelXMilliG, data.accelYMilliG, data.accelZMilliG)
                roll += normalizeDeg(measured - roll) * ROLL_SMOOTHING
                fusedYaw = yawCorrector.update(
                    rawYawDeg = rawYaw,
                    gyroXDps = data.gyroXDps.toDouble(),
                    gyroYDps = data.gyroYDps.toDouble(),
                    gyroZDps = data.gyroZDps.toDouble(),
                    timestampMs = data.timestampMs,
                ).yawDeg
            }
        }
        onDispose {
            job.cancel()
            runCatching { commands.stopImuData() }
        }
    }

    DisposableEffect(compass) {
        compass.start()
        onDispose { compass.stop() }
    }

    // 画面を出るときにグラスを片づける
    DisposableEffect(commands) {
        onDispose {
            scope.launch(NonCancellable) {
                runCatching {
                    commands.removeCanvasImage(CalibrationMarker.IMAGE_ID)
                    commands.removeCanvasImage(RIDGE_IMAGE_ID)
                    commands.sendCanvasElements(clearedCanvasText())
                }
            }
        }
    }

    // --- 稜線合わせで山を選べるように、地平線を焼いておく（**焼けていれば飛ばす**） ---
    LaunchedEffect(Unit) {
        if (session.panorama != null) {
            baking = false
            return@LaunchedEffect
        }
        val located = withContext(Dispatchers.IO) { locator.lastKnown() ?: locator.current() }
        runCatching {
            withContext(Dispatchers.Default) {
                session.bake(
                    located?.latDeg ?: ObservationDefaults.DEFAULT_LAT_DEG,
                    located?.lonDeg ?: ObservationDefaults.DEFAULT_LON_DEG,
                )
            }
        }.onFailure { note = "地平線を焼けませんでした: ${it.message}" }
        baking = false
    }

    // --- 段1: 十字を出して、静止で確定する ---
    LaunchedEffect(step) {
        if (step != Step.COARSE) return@LaunchedEffect
        val cross = withContext(Dispatchers.Default) { CalibrationMarker.grayscale() }
        runCatching {
            commands.sendCanvasElements(clearedCanvasText())
            commands.sendCanvasImage(
                id = CalibrationMarker.IMAGE_ID,
                x = (PANEL_WIDTH - CalibrationMarker.SIZE) / 2,
                y = (PANEL_HEIGHT - CalibrationMarker.SIZE) / 2,
                width = CalibrationMarker.SIZE,
                height = CalibrationMarker.SIZE,
                grayscale = cross,
            )
        }.onFailure { note = "十字を出せませんでした: ${it.message}" }

        estimator.reset()
        var started = false
        while (true) {
            val now = SystemClock.elapsedRealtime()
            val located = locator.lastKnown()
            val lat = located?.latDeg ?: ObservationDefaults.DEFAULT_LAT_DEG
            val lon = located?.lonDeg ?: ObservationDefaults.DEFAULT_LON_DEG
            accuracyText = compass.accuracyText()
            distortion = compass.quality(lat, lon, System.currentTimeMillis())?.reason

            val heading = compass.trueHeadingDeg(lat, lon, System.currentTimeMillis())
            val phonePitch = compass.pitchDeg
            val accurate = compass.accuracy >= COMPASS_ACCURACY_OK
            gate = gate.advance(now, accurate, prompting = heading != null && imuSeen)

            if (heading != null && phonePitch != null && imuSeen && gate.ready(accurate)) {
                val estimate = estimator.add(now, heading, phonePitch, fusedYaw ?: rawYaw, pitch)
                progress = (estimate.spanMs / CalibrationEstimator.WINDOW_MS.toFloat()).coerceIn(0f, 1f)
                if (!started && estimate.sampleCount > 1) {
                    started = true
                    feedback.start()
                }
                if (estimate.stable) {
                    offset = estimate.headingOffsetDeg
                    coarseDone = true
                    feedback.done()
                    note = null
                    // 焼けていなければ [PickPanel] が待ち表示を出す。**ここで諦めない**
                    step = Step.PICK_CENTER
                    return@LaunchedEffect
                }
            } else {
                progress = 0f
            }
            delay(HOLD_TICK_MS)
        }
    }

    // --- 段2: 選んだ峰を印に入れて静止する ---
    LaunchedEffect(step, target) {
        if (step != Step.HOLD_CENTER && step != Step.HOLD_EDGE) return@LaunchedEffect
        val peak = target ?: return@LaunchedEffect
        val profile = session.profile ?: return@LaunchedEffect
        val panorama = session.panorama ?: return@LaunchedEffect
        val edge = step == Step.HOLD_EDGE
        val markX = if (edge) RIDGE_WIDTH * RidgeAlignment.EDGE_MARK_RATIO else RIDGE_WIDTH / 2.0

        runCatching { commands.removeCanvasImage(CalibrationMarker.IMAGE_ID) }

        // **6DoF を待つ。** ヨーの初期値 0 のまま焼くと、印に入れる前から
        // 見当違いの稜線が出て「合わせる」動作が始められない
        while (!imuSeen) delay(HOLD_TICK_MS)

        var stillSince = 0L
        var lastAz = Double.NaN
        var lastSentAz = Double.NaN
        while (true) {
            val now = SystemClock.elapsedRealtime()
            val az = azimuthNow()

            // **合わせている最中も稜線を出し続ける。** 実物と見比べるための画面なので、
            // 絵が消えている時間があると合わせようがない。ただし送りすぎない（1 枚 0.4 秒）
            if (lastSentAz.isNaN() || abs(normalizeDeg(az - lastSentAz)) > ALIGN_REDRAW_DEG) {
                val map = withContext(Dispatchers.Default) {
                    RidgeMap.bake(profile, panorama, az, pitch, roll, fovDeg = fov)
                }
                // **印を描いてから数える。** 印は縦線なので行ごとに RLE の run が増える。
                // 数えたあとに描くと、上限判定が実際に送るものより小さい絵についてしまう
                // （[RidgeMap.bufferUsageBytes] は lazy なので、先に触ると古い値が焼き付く）
                map.gray.sightMark(map.width, map.height, markX.toInt())
                if (map.fitsBuffer) {
                    runCatching {
                        commands.sendCanvasImage(
                            id = RIDGE_IMAGE_ID,
                            x = (PANEL_WIDTH - map.width) / 2,
                            y = (PANEL_HEIGHT - map.height) / 2,
                            width = map.width,
                            height = map.height,
                            grayscale = map.gray,
                        )
                    }.onSuccess { lastSentAz = az }
                }
            }

            // 静止の判定。**押させない**ので、動いていないことだけを見る
            val moved = !lastAz.isNaN() && abs(normalizeDeg(az - lastAz)) > HOLD_STILL_DEG
            if (moved || lastAz.isNaN()) {
                if (moved && stillSince != 0L) feedback.lost()
                stillSince = now
            }
            lastAz = az
            progress = ((now - stillSince) / HOLD_MS.toFloat()).coerceIn(0f, 1f)

            if (now - stillSince >= HOLD_MS) {
                val hold = RidgeAlignment.Hold(
                    peakAzimuthDeg = peak.azimuthDeg,
                    peakAltitudeDeg = peak.altitudeDeg,
                    yawDeg = fusedYaw ?: rawYaw,
                    pitchDeg = pitch,
                    rollDeg = roll,
                    markX = markX,
                )
                if (edge) {
                    val measured = RidgeAlignment.fovDegFrom(hold, offset, RIDGE_WIDTH, RIDGE_HEIGHT)
                    if (measured == null) {
                        // **答えを作らない。** 取り違えたまま画角を書き換えるほうが害が大きい
                        note = "${peak.label}が印に入っていないようです。もう一度どうぞ"
                        feedback.lost()
                        step = Step.PICK_EDGE
                    } else {
                        fov = measured
                        fovMeasured = true
                        feedback.done()
                        note = null
                        step = Step.DONE
                    }
                } else {
                    offset = RidgeAlignment.headingOffsetFrom(hold)
                    headingAligned = true
                    centerPeak = peak
                    feedback.done()
                    note = null
                    step = Step.PICK_EDGE
                }
                return@LaunchedEffect
            }
            delay(HOLD_TICK_MS)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        MountainBackdrop(background = background, modifier = Modifier.fillMaxSize())
        Column(
            modifier = Modifier.fillMaxSize().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        ) {
            Text("方位合わせ", style = MaterialTheme.typography.headlineSmall, color = Color.White)
            Text(
                text = when (step) {
                    Step.COARSE -> "1 / 2　粗合わせ"
                    Step.PICK_CENTER, Step.HOLD_CENTER -> "2 / 2　稜線合わせ（方位）"
                    Step.PICK_EDGE, Step.HOLD_EDGE -> "2 / 2　稜線合わせ（画角）"
                    Step.DONE -> "合いました"
                },
                style = MaterialTheme.typography.labelLarge,
                color = SaberaGreen,
            )

            when (step) {
                Step.COARSE -> CoarsePanel(
                    accuracyText = accuracyText,
                    distortion = distortion,
                    bypassed = gate.bypassed,
                    imuSeen = imuSeen,
                    progress = progress,
                )

                Step.PICK_CENTER, Step.PICK_EDGE -> {
                    val edge = step == Step.PICK_EDGE
                    // **2 座目は「別の山」でなくてよい。** 画角は峰 1 座と印の位置だけで決まる
                    // （[RidgeAlignment.fovDegFrom] は 1 座しか見ない）。首を振って同じ山を
                    // 印へ寄せれば測れるので、**同じ山を先頭に出して勧める** ——
                    // 鯖江で見える山は 10 座、白山の近くには七倉山（方位差 2.1°）と
                    // 白山釈迦岳（1.1°）しかなく、**別の山を強いると取り違える**。
                    // 取り違えたまま出た画角は範囲内に収まるので捨てられない
                    PickPanel(
                        baking = baking,
                        edge = edge,
                        peaks = session.panorama?.peaks.orEmpty(),
                        confusable = session.panorama?.confusable.orEmpty(),
                        recommended = if (edge) centerPeak else null,
                        onPick = {
                            target = it
                            step = if (edge) Step.HOLD_EDGE else Step.HOLD_CENTER
                        },
                    )
                }

                Step.HOLD_CENTER, Step.HOLD_EDGE -> HoldPanel(
                    peak = target,
                    edge = step == Step.HOLD_EDGE,
                    progress = progress,
                )

                Step.DONE -> DonePanel(
                    offsetDeg = offset,
                    fovDeg = fov,
                    fovMeasured = fovMeasured,
                )
            }

            note?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = SaberaWarning,
                    textAlign = TextAlign.Center,
                )
            }

            // **出口は 1 本。** 「合わせた」も「途中で抜ける」も同じところを通して、
            // そこまで測れたぶんを必ず持ち出す（旗が実測かどうかを語る）
            val leave = {
                onCalibrated(
                    RidgeAlignment.Result(
                        headingOffsetDeg = offset,
                        fovDeg = fov,
                        headingMeasured = headingAligned,
                        fovMeasured = fovMeasured,
                        edgeRejected = false,
                    ),
                )
            }

            if (step == Step.DONE) {
                Button(
                    onClick = leave,
                    modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SaberaGreen,
                        contentColor = SaberaOnAccent,
                    ),
                ) { Text("稜線を出す") }
            }

            Row {
                TextButton(onClick = leave) {
                    Text(
                        // 粗合わせだけでも済んでいれば、それは「合わせずに」ではない
                        if (coarseDone) "ここまでで稜線を出す" else "合わせずに進む",
                        color = Color.White.copy(alpha = 0.7f),
                    )
                }
                TextButton(onClick = onHome) { Text("ホーム", color = SaberaGreen) }
            }
        }
    }
}

/** 段1 の説明と、磁気の状態。**低いまま合わせても、その誤差がそのまま稜線に乗る** */
@Composable
private fun CoarsePanel(
    accuracyText: String,
    distortion: String?,
    bypassed: Boolean,
    imuSeen: Boolean,
    progress: Float,
) {
    Panel {
        Text(
            "スマホを顔の前にかざして、グラスの十字とこの丸を重ねてください",
            color = Color.White,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "重ねたまま動かさずにいると決まります（押さなくて大丈夫です）",
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.66f),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(14.dp))
        Box(
            modifier = Modifier.size(96.dp).clip(CircleShape)
                .background(SaberaGreen.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(18.dp).clip(CircleShape).background(SaberaGreen))
        }
        Spacer(Modifier.height(14.dp))
        HoldBar(progress)
        Spacer(Modifier.height(10.dp))
        Text(
            "磁気の精度: $accuracyText",
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.7f),
        )
        if (!imuSeen) {
            Text(
                "グラスから首の向きが届いていません",
                style = MaterialTheme.typography.bodySmall,
                color = SaberaWarning,
            )
        }
        distortion?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = SaberaWarning)
        }
        if (bypassed) {
            Text(
                "磁気の精度が上がらないまま進みます。稜線合わせで詰めてください",
                style = MaterialTheme.typography.bodySmall,
                color = SaberaWarning,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 段2 の山選び。**アプリに当てさせない。** 粗合わせが ±15° ずれていると取り違える */
@Composable
private fun PickPanel(
    baking: Boolean,
    edge: Boolean,
    peaks: List<SightedPeak>,
    /**
     * **方位が 2° 以内に別の山があって取り違える山**（[PeakPanorama.confusable]）。
     *
     * 選べないようにはしない —— 実物が見えているかは使う人しか知らないので、
     * **警告して判断を渡す**。鯖江の白山は隣に白山釈迦岳（1.06°）が居るのでここに入る。
     */
    confusable: Set<SightedPeak> = emptySet(),
    /** 先頭に出して勧める山。2 座目では**1 座目と同じ山**（取り違えないので確実） */
    recommended: SightedPeak? = null,
    onPick: (SightedPeak) -> Unit,
) {
    Panel {
        if (baking) {
            LoadingPanel("見える山を調べています", "同梱の地形から作ります（数秒）")
            return@Panel
        }
        if (peaks.isEmpty()) {
            Text("見えている山がありません", color = Color.White)
            return@Panel
        }
        Text(
            if (edge) "印に入れる山を選んでください" else "実物が見えている山を 1 つ選んでください",
            color = Color.White,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            if (edge) {
                recommended?.let { "画角を測ります。${it.label}のままで大丈夫です（首を振って印に入れます）" }
                    ?: "画角を測ります。首を振って、その山を印に入れます"
            } else {
                "百名山を上に出しています。名前と形が分かる山ほど正確に合います"
            },
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.66f),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        LazyColumn(
            modifier = Modifier.fillMaxWidth().height(220.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(
                peaks.sortedWith(
                    // 勧める山（2 座目なら 1 座目と同じ山）→ 百名山 → 残り
                    compareByDescending<SightedPeak> { it.label == recommended?.label }
                        // **取り違える山は下げる。** 上に出すと、いちばん有名な山
                        // （鯖江なら白山）を勧めてしまい、隣の峰で合わせた較正が残る
                        .thenBy { it in confusable }
                        .thenByDescending { it.isFamous },
                ),
            ) { peak ->
                OutlinedButton(onClick = { onPick(peak) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(
                            peak.label + if (peak.isFamous) "（百名山）" else "",
                            color = Color.White,
                        )
                        Text(
                            "方位 %.0f°・仰角 %.1f°・%.0fkm".format(
                                peak.azimuthDeg, peak.altitudeDeg, peak.distanceM / 1000.0,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.6f),
                        )
                        if (peak in confusable) {
                            Text(
                                "すぐ隣に似た山があります（取り違えると合いません）",
                                style = MaterialTheme.typography.bodySmall,
                                color = SaberaWarning,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 静止で確定する画面。**押させない** */
@Composable
private fun HoldPanel(peak: SightedPeak?, edge: Boolean, progress: Float) {
    Panel {
        Text(
            if (edge) {
                "${peak?.label ?: "その山"}を、グラスの右寄りの線に入れて止めてください"
            } else {
                "${peak?.label ?: "その山"}を、グラスの真ん中に入れて止めてください"
            },
            color = Color.White,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "首を止めたままにすると決まります",
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.66f),
        )
        Spacer(Modifier.height(14.dp))
        HoldBar(progress)
    }
}

@Composable
private fun DonePanel(offsetDeg: Double, fovDeg: Double, fovMeasured: Boolean) {
    Panel {
        Text("合いました", style = MaterialTheme.typography.titleMedium, color = SaberaGreen)
        Spacer(Modifier.height(10.dp))
        Text("方位オフセット %.1f°".format(offsetDeg), color = Color.White)
        Spacer(Modifier.height(4.dp))
        Text(
            if (fovMeasured) "画角 %.1f°（実測）".format(fovDeg) else "画角 %.1f°（未実測の仮値）".format(fovDeg),
            color = if (fovMeasured) Color.White else SaberaWarning,
        )
    }
}

/** 満ちると決まる帯。**押していないのに終わる**ので、進み具合が見えないと不安になる */
@Composable
private fun HoldBar(progress: Float) {
    LinearProgressIndicator(
        progress = { progress },
        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
        color = SaberaGreen,
        trackColor = Color.White.copy(alpha = 0.15f),
    )
}

@Composable
private fun Panel(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().widthIn(max = 420.dp),
        colors = CardDefaults.cardColors(containerColor = SaberaSurface),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content,
        )
    }
}

private enum class Step { COARSE, PICK_CENTER, HOLD_CENTER, PICK_EDGE, HOLD_EDGE, DONE }

/** OS の信頼度がこれ以上なら粗合わせを通す（`SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM`） */
private const val COMPASS_ACCURACY_OK = 2

/** 静止と認める首の振れ[度]。6DoF のふらつきは 1 度に届かない */
private const val HOLD_STILL_DEG = 1.0

/** これだけ止めたら決まる。短いと通りすがりで決まり、長いと腕がもたない */
private const val HOLD_MS = 1_400L

private const val HOLD_TICK_MS = 100L

/** 合わせている最中に稜線を送り直す間隔[度]。**送りすぎると絵が消えている時間が増える** */
private const val ALIGN_REDRAW_DEG = 4.0
