package com.dcontrol.client.feature.findphone.domain

import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import androidx.core.location.LocationManagerCompat
import androidx.core.os.CancellationSignal
import androidx.core.util.Consumer
import com.dcontrol.client.core.websocket.protocol.Location
import com.dcontrol.client.core.websocket.protocol.LocationProviders
import com.dcontrol.client.core.websocket.protocol.Phase3Text
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToLong
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import android.location.Location as AndroidLocation

/** The outcome of a `find.locate`, mapped to protocol results and errors by the command handler. */
sealed interface LocateResult {
    data class Fresh(val location: Location) : LocateResult
    data class Stale(val location: Location) : LocateResult

    /** No fix at all, fresh or last-known. */
    data object None : LocateResult

    /** Location is turned off (no provider enabled). */
    data object Disabled : LocateResult
}

/**
 * Tries a fresh fix from the best enabled provider for up to 10 s, then falls back to the newest last-known
 * fix (marked stale). No Play services: it uses the platform [LocationManagerCompat] only.
 */
@Singleton
class Locator @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun locate(timeoutMs: Long = FRESH_TIMEOUT_MS): LocateResult {
        val manager = context.getSystemService(LocationManager::class.java) ?: return LocateResult.Disabled
        val provider = bestProvider(manager) ?: return LocateResult.Disabled

        val fresh = withTimeoutOrNull(timeoutMs) { currentLocation(manager, provider) }
        if (fresh != null) return LocateResult.Fresh(map(fresh, stale = false))

        val last = lastKnown(manager)
        return if (last != null) LocateResult.Stale(map(last, stale = true)) else LocateResult.None
    }

    // The location permission is verified by FindCommands (via the RuntimePermissions seam) before locate() runs;
    // lint can't follow that cross-class check, so suppress it here.
    @SuppressLint("MissingPermission")
    private suspend fun currentLocation(manager: LocationManager, provider: String): AndroidLocation? =
        suspendCancellableCoroutine { continuation ->
            val executor = Executor { it.run() }
            val signal: CancellationSignal? = null
            val consumer = Consumer<AndroidLocation?> { location ->
                if (continuation.isActive) continuation.resumeWith(Result.success(location))
            }
            LocationManagerCompat.getCurrentLocation(manager, provider, signal, executor, consumer)
        }

    private fun bestProvider(manager: LocationManager): String? =
        PROVIDER_ORDER.firstOrNull { safeEnabled(manager, it) }

    @SuppressLint("MissingPermission")
    private fun lastKnown(manager: LocationManager): AndroidLocation? =
        PROVIDER_ORDER.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }

    private fun safeEnabled(manager: LocationManager, provider: String): Boolean =
        runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false)

    private fun map(location: AndroidLocation, stale: Boolean): Location = Location(
        latitude = round6(location.latitude),
        longitude = round6(location.longitude),
        accuracyM = round1(location.accuracy.toDouble()),
        altitudeM = if (location.hasAltitude()) round1(location.altitude) else null,
        fixedAt = Phase3Text.timestamp(location.time),
        provider = provider(location.provider),
        stale = stale,
    )

    private fun provider(name: String?): String = when (name) {
        LocationManager.GPS_PROVIDER -> LocationProviders.GPS
        LocationManager.NETWORK_PROVIDER -> LocationProviders.NETWORK
        LocationManager.PASSIVE_PROVIDER -> LocationProviders.PASSIVE
        "fused" -> LocationProviders.FUSED
        else -> LocationProviders.OTHER
    }

    private fun round6(value: Double): Double = (value * 1_000_000).roundToLong() / 1_000_000.0

    private fun round1(value: Double): Double = (value * 10).roundToLong() / 10.0

    private companion object {
        const val FRESH_TIMEOUT_MS = 10_000L
        val PROVIDER_ORDER = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        )
    }
}
