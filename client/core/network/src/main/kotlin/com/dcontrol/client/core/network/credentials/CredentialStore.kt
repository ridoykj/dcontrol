package com.dcontrol.client.core.network.credentials

import com.dcontrol.client.core.websocket.DeviceCredentials
import java.net.URI
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Serializable
data class StoredCredentials(
    val baseUrl: String,
    val deviceId: String,
    val deviceSecret: String,
    val deviceName: String,
) {
    val host: String
        get() = runCatching { URI(baseUrl).authority }.getOrNull() ?: baseUrl

    fun toDeviceCredentials() = DeviceCredentials(baseUrl, deviceId, deviceSecret)

    override fun toString(): String = "StoredCredentials(baseUrl=$baseUrl, deviceId=$deviceId, deviceName=$deviceName)"
}

interface CredentialStore {
    /** Emits null when the phone isn't paired. */
    val credentials: Flow<StoredCredentials?>

    suspend fun save(credentials: StoredCredentials)

    suspend fun clear()
}
