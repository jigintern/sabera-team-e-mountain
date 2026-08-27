package jp.jig.glasses.sample.kmp.ui

import android.os.SystemClock
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.jigglass.glass.GlassClient
import app.jigglass.glass.GlassManager
import jp.jig.glasses.sample.kmp.geo.ObservationDefaults
import jp.jig.glasses.sample.kmp.glass.BundledData
import jp.jig.glasses.sample.kmp.terrain.RidgeSession
import jp.jig.glasses.sample.kmp.ui.component.MountainBackground
import jp.jig.glasses.sample.kmp.ui.component.SaberaGreen
import jp.jig.glasses.sample.kmp.ui.component.SaberaOnAccent
import jp.jig.glasses.sample.kmp.ui.component.SaberaSurface
import jp.jig.glasses.sample.kmp.ui.component.SaberaWarning
import jp.jig.glasses.sample.kmp.ui.component.rememberMountainBackground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * ホームから始めて、接続確認 → 方位合わせ → 稜線と進む。
 *
 * **星しるべ `GlassesApp` の骨格をそのまま持ってきている**（画面の持ち方・戻るキーの扱い・
 * 瞬断の見張り・切断の一本化）。台本・BGM・通知は峰ミルに無いので落としてある。
 */
@Composable
fun MinemiruApp(manager: GlassManager) {
    val context = LocalContext.current
    var screen by rememberSaveable { mutableStateOf(AppScreen.HOME) }
    val background = rememberMountainBackground()

    /**
     * 焼いた地平線。**画面ではなくアプリが持つ。**
     *
     * 焼くのに実機で 2.8 秒かかるので、方位合わせと稜線の画面がそれぞれ焼くと
     * **合わせ終わった直後にもう一度 2.8 秒待たされる**。同梱データも
     * プロセスで一度だけ読む（[BundledData]）。
     */
    val session = remember {
        RidgeSession(BundledData.elevation(context), BundledData.peaks(context))
    }

    /** 方位合わせの結果。**画面をまたいで持ち回る**（稜線の画面が毎回作り直すものではない） */
    var headingOffset by rememberSaveable { mutableDoubleStateOf(0.0) }

    /**
     * 画角。**既定は星しるべ由来の未実測の仮値**で、稜線合わせで実測値に置き換わる。
     * 実測できたかどうかも持つ —— 「合わせた」と「合わせたつもり」は画面で区別する。
     */
    var fovDeg by rememberSaveable { mutableDoubleStateOf(ObservationDefaults.FOV_DEG) }
    var fovMeasured by rememberSaveable { mutableStateOf(false) }

    /** 方位オフセットを稜線合わせ（2 段目）で取ったか。粗合わせだけなら false */
    var headingMeasured by rememberSaveable { mutableStateOf(false) }

    val connectedClient by manager.connectedDevice.collectAsState(initial = null)
    var observingClient by remember { mutableStateOf<GlassClient?>(null) }
    var connectionLost by rememberSaveable { mutableStateOf(false) }
    var confirmLeaving by rememberSaveable { mutableStateOf(false) }
    var pendingDisconnect by remember { mutableStateOf<GlassClient?>(null) }

    /** ホームのひとこと。**同梱カタログから作る**ので圏外でも出る */
    var tip by remember { mutableStateOf<MountainTip?>(null) }
    LaunchedEffect(Unit) {
        tip = withContext(Dispatchers.Default) {
            runCatching {
                val famous = BundledData.peaks(context).famous
                val peak = famous[Random.nextInt(famous.size)]
                MountainTip(
                    header = "日本百名山 ${famous.size} 座を収録",
                    text = "${peak.label}（${peak.elevationM}m・${peak.pref}）" +
                        " —— 150km 以内にあって、手前の山に隠れていなければ名前が出ます",
                )
            }.getOrNull()
        }
    }

    /**
     * 切断待ちの相手。**ホームへ移してから切る。**
     *
     * 稜線を出したまま切ると、観測中だけ動いている見張り（[ConnectionWatch]）が
     * 「接続が切れました」を誤爆する。
     */
    LaunchedEffect(pendingDisconnect) {
        val target = pendingDisconnect ?: return@LaunchedEffect
        withContext(Dispatchers.IO) { runCatching { manager.disconnect(target) } }
            .onFailure { Log.w(TAG, "SABERA の切断に失敗した", it) }
        observingClient = null
        pendingDisconnect = null
    }

    // connectedDevice は切断直後に null になるので、猶予の間も見られるよう最後の client を控える
    LaunchedEffect(connectedClient) {
        if (connectedClient != null) observingClient = connectedClient
    }

    // 瞬断で観測画面を追い出さない。**BLE は屋外でよく途切れる**
    LaunchedEffect(screen, observingClient) {
        if (screen != AppScreen.CALIBRATION && screen != AppScreen.RIDGE) {
            connectionLost = false
            return@LaunchedEffect
        }
        val client = observingClient ?: return@LaunchedEffect
        val watch = ConnectionWatch()
        while (true) {
            val alive = manager.connectedDevice.value != null && client.connected.value
            if (watch.sample(alive, SystemClock.elapsedRealtime())) connectionLost = true
            delay(CONNECTION_CHECK_INTERVAL_MS)
        }
    }

    /**
     * 戻るキー。**既定のままだと戻るキーでアプリが終わる。**
     * 稜線の画面でそれをやると方位合わせからやり直しなので、確認を挟む。
     */
    BackHandler(enabled = screen != AppScreen.HOME) {
        when (val back = backDestination(screen)) {
            null -> if (screen == AppScreen.RIDGE) confirmLeaving = true
            else -> screen = back
        }
    }

    when (screen) {
        AppScreen.HOME -> HomeScreen(
            background = background,
            tip = tip,
            onStart = { screen = AppScreen.CONNECTION },
        )

        AppScreen.CONNECTION -> ConnectionCheckScreen(
            manager = manager,
            client = connectedClient,
            background = background,
            onContinue = { screen = AppScreen.CALIBRATION },
            onHome = { screen = AppScreen.HOME },
            // **画面は変えない。** 切れるとカードがそのまま未接続の表示へ変わり、選び直せる
            onDisconnect = { pendingDisconnect = connectedClient ?: observingClient },
        )

        AppScreen.CALIBRATION -> WithClient(
            client = observingClient,
            manager = manager,
            background = background,
            onHome = { screen = AppScreen.HOME },
            onRetry = { screen = AppScreen.CALIBRATION },
        ) { client ->
            CalibrationScreen(
                client = client,
                session = session,
                background = background,
                headingOffsetDeg = headingOffset,
                fovDeg = fovDeg,
                onCalibrated = { result ->
                    headingOffset = result.headingOffsetDeg
                    fovDeg = result.fovDeg
                    // **一度でも実測できたら下げない。** 合わせ直しで途中でやめても、
                    // 前に取った実測値はそのまま使い続けている
                    if (result.fovMeasured) fovMeasured = true
                    if (result.headingMeasured) headingMeasured = true
                    screen = AppScreen.RIDGE
                },
                onHome = { screen = AppScreen.HOME },
            )
        }

        AppScreen.RIDGE -> WithClient(
            client = observingClient,
            manager = manager,
            background = background,
            onHome = { screen = AppScreen.HOME },
            onRetry = { screen = AppScreen.CALIBRATION },
        ) { client ->
            RidgeScreen(
                client = client,
                session = session,
                background = background,
                headingOffsetDeg = headingOffset,
                fovDeg = fovDeg,
                fovMeasured = fovMeasured,
                headingMeasured = headingMeasured,
                onRecalibrate = { screen = AppScreen.CALIBRATION },
                // ボタンは**確認を出すだけ**。切断は戻るキーと同じ出口に合流させる
                onRequestLeave = { confirmLeaving = true },
            )
        }
    }

    if (connectionLost) {
        ConnectionLostDialog(
            onConnectionCheck = {
                connectionLost = false
                observingClient = null
                screen = AppScreen.CONNECTION
            },
        )
    }

    // 切断のダイアログが出ているなら、そちらが先。重ねて出さない
    if (confirmLeaving && !connectionLost) {
        LeaveDialog(
            onLeave = {
                confirmLeaving = false
                // 生きているほうを先に採る。disconnect の突き合わせは**参照等価**なので、
                // 控えのほうを渡すと connectedDevice が null にならない
                pendingDisconnect = connectedClient ?: observingClient
                screen = AppScreen.HOME
            },
            onStay = { confirmLeaving = false },
        )
    }
}

/**
 * つながっている前提の画面を、**つながっていないときは接続確認に差し替える**。
 *
 * 星しるべが同じことを画面ごとに書いていた（3 か所）ので、こちらは 1 か所にまとめる。
 */
@Composable
private fun WithClient(
    client: GlassClient?,
    manager: GlassManager,
    background: MountainBackground,
    onHome: () -> Unit,
    onRetry: () -> Unit,
    content: @Composable (GlassClient) -> Unit,
) {
    if (client == null) {
        ConnectionCheckScreen(
            manager = manager,
            client = null,
            background = background,
            onContinue = onRetry,
            onHome = onHome,
            // 未接続の代替表示なので、つなぎ直す口はそもそも出ない
            onDisconnect = {},
        )
    } else {
        content(client)
    }
}

/**
 * やめるかの確認。**稜線から出る唯一の出口**（戻るキーもボタンもここへ集まる）。
 *
 * **やめると SABERA との接続まで切れる。** CDM の登録が消えるので、もう一度始めるには
 * 端末を選び直して方位合わせからやり直しになる。**代償は本文とボタンの側に書く** —
 * 見出しを「切断」にすると、やめたいだけの人の意図から遠くなる。
 */
@Composable
private fun LeaveDialog(onLeave: () -> Unit, onStay: () -> Unit) {
    Dialog(onDismissRequest = onStay) {
        Card(
            modifier = Modifier.fillMaxWidth().widthIn(max = 360.dp),
            colors = CardDefaults.cardColors(containerColor = SaberaSurface),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "山を見るのをやめますか",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "SABERA との接続を切ってホームへ戻ります。" +
                        "次に始めるときは、SABERA を選び直して方位合わせからやり直しです",
                    color = Color.White.copy(alpha = 0.68f),
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = onStay,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SaberaGreen,
                        contentColor = SaberaOnAccent,
                    ),
                ) { Text("続ける") }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onLeave, modifier = Modifier.fillMaxWidth()) {
                    Text("切断してホームへ", color = SaberaWarning)
                }
            }
        }
    }
}

@Composable
private fun ConnectionLostDialog(onConnectionCheck: () -> Unit) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth().widthIn(max = 360.dp),
            colors = CardDefaults.cardColors(containerColor = SaberaSurface),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "SABERA との接続が切れました",
                    style = MaterialTheme.typography.titleLarge,
                    color = SaberaWarning,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "SABERA の電源と Bluetooth を確認してください",
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = onConnectionCheck,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SaberaGreen,
                        contentColor = SaberaOnAccent,
                    ),
                ) { Text("接続確認へ") }
            }
        }
    }
}

private const val TAG = "MinemiruApp"
private const val CONNECTION_CHECK_INTERVAL_MS = 1_000L
