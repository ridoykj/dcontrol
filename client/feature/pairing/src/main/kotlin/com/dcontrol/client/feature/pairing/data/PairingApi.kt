package com.dcontrol.client.feature.pairing.data

import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.POST

interface PairingApi {
    @POST("api/device-api/pairings/preview")
    suspend fun preview(@Body body: PreviewRequest): PreviewResponse

    @POST("api/device-api/pairings/claim")
    suspend fun claim(@Body body: ClaimRequest): ClaimResponse
}

@Serializable
data class PreviewRequest(val token: String)

@Serializable
data class PreviewResponse(val accountEmail: String, val expiresAt: String)

@Serializable
data class ClaimRequest(val token: String, val device: ClaimDevice)

@Serializable
data class ClaimDevice(
    val name: String,
    val manufacturer: String,
    val model: String,
    val androidVersion: String,
    val sdkInt: Int,
    val appVersion: String,
)

@Serializable
data class ClaimResponse(val deviceId: String, val deviceSecret: String, val deviceName: String)
