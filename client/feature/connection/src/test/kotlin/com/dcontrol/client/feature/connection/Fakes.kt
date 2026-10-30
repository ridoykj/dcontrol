package com.dcontrol.client.feature.connection

import com.dcontrol.client.core.network.credentials.CredentialStore
import com.dcontrol.client.core.network.credentials.StoredCredentials
import com.dcontrol.client.core.websocket.ConnectionState
import com.dcontrol.client.core.websocket.DeviceCredentials
import com.dcontrol.client.feature.connection.domain.ConnectionController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

class FakeConnectionController : ConnectionController {
    override val state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val started = mutableListOf<DeviceCredentials>()
    var stopped = 0

    override fun start(credentials: DeviceCredentials) {
        started += credentials
    }

    override fun stop() {
        stopped++
        state.value = ConnectionState.Idle
    }
}

class FakeCredentialStore(initial: StoredCredentials? = null) : CredentialStore {
    private val flow = MutableStateFlow(initial)
    override val credentials: StateFlow<StoredCredentials?> = flow

    override suspend fun save(credentials: StoredCredentials) {
        flow.value = credentials
    }

    override suspend fun clear() {
        flow.value = null
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val dispatcher: TestDispatcher = UnconfinedTestDispatcher(),
) : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)

    override fun finished(description: Description) = Dispatchers.resetMain()
}
