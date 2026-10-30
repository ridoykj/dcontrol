package com.dcontrol.client.feature.pairing.ui

import app.cash.turbine.test
import com.dcontrol.client.core.network.api.ApiException
import com.dcontrol.client.feature.pairing.MainDispatcherRule
import com.dcontrol.client.feature.pairing.domain.PairedDevice
import com.dcontrol.client.feature.pairing.domain.PairingPayload
import com.dcontrol.client.feature.pairing.domain.PairingPreview
import com.dcontrol.client.feature.pairing.domain.PairingRepository
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PairingViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val qr = "dcontrol://pair?v=1&u=http%3A%2F%2F10.0.2.2%3A8080&t=token123"
    private val expiry = Instant.parse("2026-10-29T10:18:30Z")

    private class FakeRepository : PairingRepository {
        var previewResult: CompletableDeferred<PairingPreview> = CompletableDeferred()
        var claimResult: CompletableDeferred<PairedDevice> = CompletableDeferred()
        val claims = mutableListOf<Pair<PairingPayload, String>>()

        override fun defaultDeviceName() = "Pixel 9"
        override suspend fun preview(payload: PairingPayload) = previewResult.await()
        override suspend fun claim(payload: PairingPayload, deviceName: String): PairedDevice {
            claims += payload to deviceName
            return claimResult.await()
        }
    }

    private val repository = FakeRepository()

    @Test
    fun `scan shows preview then pairing succeeds`() = runTest {
        val viewModel = PairingViewModel(repository)

        viewModel.uiState.test {
            assertEquals(PairingUiState(deviceName = "Pixel 9"), awaitItem())

            viewModel.onCodeScanned(qr)
            val loading = awaitItem()
            assertEquals(PairingStep.LoadingPreview, loading.step)
            assertEquals("10.0.2.2:8080", loading.host)

            repository.previewResult.complete(PairingPreview("r***@example.com", expiry))
            val confirm = awaitItem()
            assertEquals(PairingStep.Confirm, confirm.step)
            assertEquals("r***@example.com", confirm.accountEmail)
            assertTrue(confirm.canConfirm)

            viewModel.onDeviceNameChange("  Work phone ")
            awaitItem()
            viewModel.onConfirm()
            assertEquals(PairingStep.Claiming, awaitItem().step)

            repository.claimResult.complete(PairedDevice("dev-1", "Work phone"))
            assertEquals(PairingStep.Paired, awaitItem().step)
        }
        assertEquals(PairingPayload("http://10.0.2.2:8080", "token123") to "Work phone", repository.claims.single())
    }

    @Test
    fun `invalid code keeps scanning with an error`() = runTest {
        val viewModel = PairingViewModel(repository)

        viewModel.onCodeScanned("https://example.com")

        val state = viewModel.uiState.value
        assertEquals(PairingStep.Scan, state.step)
        assertEquals(PairingError.InvalidCode, state.error)
    }

    @Test
    fun `expired token on preview returns to scanning`() = runTest {
        val viewModel = PairingViewModel(repository)
        repository.previewResult.completeExceptionally(ApiException(410, "PAIRING_TOKEN_INVALID", "gone"))

        viewModel.onCodeScanned(qr)

        val state = viewModel.uiState.value
        assertEquals(PairingStep.Scan, state.step)
        assertEquals(PairingError.Expired, state.error)
    }

    @Test
    fun `network failure on claim stays on confirm`() = runTest {
        val viewModel = PairingViewModel(repository)
        repository.previewResult.complete(PairingPreview("r***@example.com", expiry))
        repository.claimResult.completeExceptionally(ApiException(null, ApiException.NETWORK, "offline"))
        viewModel.onCodeScanned(qr)

        viewModel.onConfirm()

        val state = viewModel.uiState.value
        assertEquals(PairingStep.Confirm, state.step)
        assertEquals(PairingError.Network, state.error)
    }

    @Test
    fun `blank device name cannot be confirmed`() = runTest {
        val viewModel = PairingViewModel(repository)
        repository.previewResult.complete(PairingPreview("r***@example.com", expiry))
        viewModel.onCodeScanned(qr)

        viewModel.onDeviceNameChange("   ")
        viewModel.onConfirm()

        assertEquals(PairingStep.Confirm, viewModel.uiState.value.step)
        assertTrue(repository.claims.isEmpty())
    }

    @Test
    fun `scans are ignored while a code is being checked`() = runTest {
        val viewModel = PairingViewModel(repository)
        viewModel.onCodeScanned(qr)

        viewModel.onCodeScanned("dcontrol://pair?v=1&u=http%3A%2F%2Fother&t=other")

        assertEquals("10.0.2.2:8080", viewModel.uiState.value.host)
    }

    @Test
    fun `denied local network access stays on scanning with its own error`() = runTest {
        val viewModel = PairingViewModel(repository)

        viewModel.onLocalNetworkDenied()

        assertEquals(PairingStep.Scan, viewModel.uiState.value.step)
        assertEquals(PairingError.LocalNetworkDenied, viewModel.uiState.value.error)
        assertTrue(repository.claims.isEmpty())
    }

    @Test
    fun `cancel resets to scanning and keeps the name`() = runTest {
        val viewModel = PairingViewModel(repository)
        repository.previewResult.complete(PairingPreview("r***@example.com", expiry))
        viewModel.onCodeScanned(qr)
        viewModel.onDeviceNameChange("Tablet")

        viewModel.onCancel()

        assertEquals(PairingUiState(deviceName = "Tablet"), viewModel.uiState.value)
    }
}
