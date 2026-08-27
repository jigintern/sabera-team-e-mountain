package jp.jig.glasses.sample.kmp.alignment

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 観測地をスマホから取る。
 *
 * **星より要求がきつい。** 星は 1km ずれても位置が 0.01° も動かないが、
 * 山は有限距離にあるので**50m 動けば地平線を焼き直す**
 * （[jp.jig.glasses.sample.kmp.geo.ObservationDefaults.REBAKE_DISTANCE_M]）。
 * 数十メートルの精度はそのまま効くので、直近の値で始めて測り直しで詰める。
 *
 * **標高は返さない。返してはいけない。** GPS の標高は ±20m ずれ、観測者が地面より低いと
 * 平地そのものが壁になって稜線がまるごと壊れる（観測者 0m で白山が 11.31° に出た実測がある）。
 * 標高は同梱 DEM から引く（[jp.jig.glasses.sample.kmp.terrain.RidgeSession.bake]）。
 */
class Locator(private val context: Context) {

    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    val granted: Boolean
        get() = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** 直近の測位。すぐ返るが古いことがある */
    fun lastKnown(): Located? {
        if (!granted) return null
        return PROVIDERS.asSequence()
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
            ?.let { Located(it.latitude, it.longitude, it.provider ?: "?", it.time) }
    }

    /** いま測り直す。屋内では返らないことがあるので、呼ぶ側でタイムアウトする */
    suspend fun current(): Located? {
        if (!granted) return null
        for (provider in PROVIDERS) {
            if (!runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false)) continue
            val location = awaitLocation(provider) ?: continue
            return Located(location.latitude, location.longitude, provider, location.time)
        }
        return null
    }

    private suspend fun awaitLocation(provider: String): Location? = suspendCancellableCoroutine { cont ->
        val signal = CancellationSignal()
        cont.invokeOnCancellation { signal.cancel() }
        runCatching {
            manager.getCurrentLocation(provider, signal, context.mainExecutor) { location ->
                if (cont.isActive) cont.resume(location)
            }
        }.onFailure {
            if (cont.isActive) cont.resume(null)
        }
    }

    private companion object {
        /** 融合 → GPS → 基地局の順。屋内では GPS が返らないので基地局まで落とす */
        val PROVIDERS = listOf(
            LocationManager.FUSED_PROVIDER,
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
        )
    }
}

/**
 * 測位の結果。どの経路でいつ取れたかを画面に出したいので持っておく。
 *
 * **標高は持たない**（[Locator] の説明を参照）。
 */
class Located(val latDeg: Double, val lonDeg: Double, val provider: String, val atMillis: Long)
