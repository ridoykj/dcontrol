package com.dcontrol.client.feature.pairing.data

import com.dcontrol.client.core.network.api.ApiException
import com.dcontrol.client.core.network.api.RetrofitFactory
import com.dcontrol.client.core.network.api.apiCall
import com.dcontrol.client.core.network.credentials.CredentialStore
import com.dcontrol.client.core.network.credentials.StoredCredentials
import com.dcontrol.client.core.network.device.DeviceInfoProvider
import com.dcontrol.client.feature.pairing.domain.PairedDevice
import com.dcontrol.client.feature.pairing.domain.PairingPayload
import com.dcontrol.client.feature.pairing.domain.PairingPreview
import com.dcontrol.client.feature.pairing.domain.PairingRepository
import java.time.Instant
import java.time.format.DateTimeParseException
import javax.inject.Inject
import kotlinx.serialization.json.Json

class DefaultPairingRepository @Inject constructor(
    private val retrofitFactory: RetrofitFactory,
    private val credentialStore: CredentialStore,
    private val deviceInfoProvider: DeviceInfoProvider,
    private val json: Json,
) : PairingRepository {

    override fun defaultDeviceName(): String = deviceInfoProvider.describe().defaultName

    override suspend fun preview(payload: PairingPayload): PairingPreview {
        val response = apiCall(json) { api(payload).preview(PreviewRequest(payload.token)) }
        val expiresAt = try {
            Instant.parse(response.expiresAt)
        } catch (e: DateTimeParseException) {
            throw ApiException(null, ApiException.INVALID_RESPONSE, "Invalid expiry time", e)
        }
        return PairingPreview(response.accountEmail, expiresAt)
    }

    override suspend fun claim(payload: PairingPayload, deviceName: String): PairedDevice {
        val description = deviceInfoProvider.describe()
        val request = ClaimRequest(
            token = payload.token,
            device = ClaimDevice(
                name = deviceName.trim().take(64),
                manufacturer = description.manufacturer,
                model = description.model,
                androidVersion = description.androidVersion,
                sdkInt = description.sdkInt,
                appVersion = description.appVersion,
            ),
        )
        val response = apiCall(json) { api(payload).claim(request) }
        credentialStore.save(
            StoredCredentials(
                baseUrl = payload.baseUrl,
                deviceId = response.deviceId,
                deviceSecret = response.deviceSecret,
                deviceName = response.deviceName,
            ),
        )
        return PairedDevice(response.deviceId, response.deviceName)
    }

    private fun api(payload: PairingPayload): PairingApi =
        retrofitFactory.create(payload.baseUrl).create(PairingApi::class.java)
}
