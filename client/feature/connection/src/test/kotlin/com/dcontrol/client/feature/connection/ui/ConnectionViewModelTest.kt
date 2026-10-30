package com.dcontrol.client.feature.connection.ui

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import com.dcontrol.client.core.network.credentials.StoredCredentials
import com.dcontrol.client.core.websocket.ConnectionState
import com.dcontrol.client.feature.connection.FakeConnectionController
import com.dcontrol.client.feature.connection.FakeCredentialStore
import com.dcontrol.client.feature.connection.MainDispatcherRule
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class ConnectionViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val credentials = StoredCredentials("http://10.0.2.2:8080", "dev-1", "secret", "Pixel 9")
    private val controller = FakeConnectionController()
    private val store = FakeCredentialStore(credentials)

    private suspend fun <T> ReceiveTurbine<T>.awaitMatching(predicate: (T) -> Boolean): T {
        while (true) {
            val item = awaitItem()
            if (predicate(item)) return item
        }
    }

    @Test
    fun `reflects connection state and paired controller`() = runTest {
        val viewModel = ConnectionViewModel(controller, store)

        viewModel.uiState.test {
            assertEquals(
                ConnectionUiState(isPaired = true, deviceName = "Pixel 9", controllerHost = "10.0.2.2:8080", status = ConnectionStatus.Idle),
                awaitMatching { it.isPaired },
            )

            controller.state.value = ConnectionState.Online("Work phone", 30)
            val online = awaitItem()
            assertEquals(ConnectionStatus.Online, online.status)
            assertEquals("Work phone", online.deviceName)

            controller.state.value = ConnectionState.Reconnecting(4.seconds, "closed")
            val reconnecting = awaitItem()
            assertEquals(ConnectionStatus.Reconnecting, reconnecting.status)
            assertEquals(4L, reconnecting.retryInSeconds)
        }
    }

    @Test
    fun `unpair stops the connection and forgets credentials`() = runTest {
        val viewModel = ConnectionViewModel(controller, store)

        viewModel.uiState.test {
            awaitMatching { it.isPaired }
            viewModel.unpair()

            val state = awaitMatching { !it.isPaired }
            assertNull(state.controllerHost)
        }
        assertEquals(1, controller.stopped)
        assertNull(store.credentials.value)
    }
}
