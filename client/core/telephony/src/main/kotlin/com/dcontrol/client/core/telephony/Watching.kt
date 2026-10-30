package com.dcontrol.client.core.telephony

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay

/**
 * Waits until every permission is granted (checking every [pollEvery]), then runs [block]. Watchers use it
 * so a permission granted while the phone is online starts the observer without a reconnect.
 */
suspend fun RuntimePermissions.whenGranted(
    vararg permissions: String,
    pollEvery: Duration = 30.seconds,
    block: suspend () -> Unit,
) {
    while (!permissions.all { isGranted(it) }) delay(pollEvery)
    block()
}
