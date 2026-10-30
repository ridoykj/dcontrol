package com.dcontrol.client.core.network.device

import com.dcontrol.client.core.network.credentials.CredentialStore
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Thrown when an HTTP call needs device credentials but the phone is not paired. */
class NotPairedException : IOException("The phone is not paired")

/**
 * HTTP calls the phone makes as itself (`/api/device-api/...`), authenticated with
 * `Authorization: Device <deviceId>.<secret>` like the device WebSocket.
 */
@Singleton
class DeviceHttpClient @Inject constructor(
    private val client: OkHttpClient,
    private val credentialStore: CredentialStore,
) {
    /** Executes the request on the IO dispatcher; the caller must close the response. */
    suspend fun execute(path: String, configure: Request.Builder.() -> Unit = {}): Response {
        val credentials = credentialStore.credentials.first() ?: throw NotPairedException()
        val request = Request.Builder()
            .url(credentials.baseUrl.trimEnd('/') + path)
            .header("Authorization", credentials.toDeviceCredentials().authorizationHeader)
            .apply(configure)
            .build()
        return withContext(Dispatchers.IO) { client.newCall(request).execute() }
    }
}
