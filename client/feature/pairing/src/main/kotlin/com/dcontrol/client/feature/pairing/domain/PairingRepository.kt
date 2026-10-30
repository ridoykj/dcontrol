package com.dcontrol.client.feature.pairing.domain

import java.time.Instant

data class PairingPreview(val accountEmail: String, val expiresAt: Instant)

data class PairedDevice(val deviceId: String, val deviceName: String)

interface PairingRepository {
    fun defaultDeviceName(): String

    /** Shows who the phone would be paired with, without consuming the token. */
    suspend fun preview(payload: PairingPayload): PairingPreview

    /** Claims the token and stores the returned device credentials. */
    suspend fun claim(payload: PairingPayload, deviceName: String): PairedDevice
}
