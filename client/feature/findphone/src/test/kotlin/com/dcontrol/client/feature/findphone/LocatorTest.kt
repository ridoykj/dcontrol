package com.dcontrol.client.feature.findphone

import android.content.Context
import android.location.Location
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import com.dcontrol.client.core.websocket.protocol.LocationProviders
import com.dcontrol.client.feature.findphone.domain.LocateResult
import com.dcontrol.client.feature.findphone.domain.Locator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class LocatorTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(LocationManager::class.java)
    private val locator = Locator(context)

    @Test
    fun `no enabled provider means location is off`() = runTest {
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, false)
        shadowOf(manager).setProviderEnabled(LocationManager.NETWORK_PROVIDER, false)
        shadowOf(manager).setProviderEnabled(LocationManager.PASSIVE_PROVIDER, false)

        assertEquals(LocateResult.Disabled, locator.locate(timeoutMs = 50))
    }

    @Test
    fun `no fix at all yields None`() = runTest {
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)

        assertEquals(LocateResult.None, locator.locate(timeoutMs = 50))
    }

    @Test
    fun `maps a known fix to rounded coordinates and the provider`() = runTest {
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        val fix = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = 23.8103312
            longitude = 90.4125181
            accuracy = 12.5f
            time = 1_700_000_000_000
        }
        shadowOf(manager).setLastKnownLocation(LocationManager.GPS_PROVIDER, fix)

        // Robolectric's getCurrentLocation delivers the last-known fix, so it comes back as Fresh here; the
        // stale fall-back path (a real timeout) can't be forced with this shadow. We assert the field mapping.
        val result = locator.locate(timeoutMs = 50)
        val location = when (result) {
            is LocateResult.Fresh -> result.location
            is LocateResult.Stale -> result.location
            else -> error("expected a location, got $result")
        }
        assertEquals(23.810331, location.latitude, 0.0)
        assertEquals(90.412518, location.longitude, 0.0)
        assertEquals(12.5, location.accuracyM, 0.0)
        assertEquals(LocationProviders.GPS, location.provider)
    }
}
