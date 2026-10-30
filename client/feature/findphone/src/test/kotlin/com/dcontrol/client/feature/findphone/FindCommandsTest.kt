package com.dcontrol.client.feature.findphone

import android.Manifest
import android.content.Context
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import com.dcontrol.client.core.accessibility.AccessibilityBridge
import com.dcontrol.client.core.telephony.RuntimePermissions
import com.dcontrol.client.core.websocket.CommandException
import com.dcontrol.client.core.websocket.CommandHandler
import com.dcontrol.client.core.websocket.protocol.Envelope
import com.dcontrol.client.core.websocket.protocol.ErrorCodes
import com.dcontrol.client.core.websocket.protocol.FindLockRequest
import com.dcontrol.client.core.websocket.protocol.FindRingRequest
import com.dcontrol.client.core.websocket.protocol.Permissions
import com.dcontrol.client.core.websocket.protocol.ProtocolCodec
import com.dcontrol.client.feature.findphone.data.FindCommands
import com.dcontrol.client.feature.findphone.domain.Locator
import com.dcontrol.client.feature.findphone.domain.Locker
import com.dcontrol.client.feature.findphone.domain.Ringer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class FindCommandsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val codec = ProtocolCodec()
    private val player = FakeTonePlayer()
    private val lockNotifier = FakeLockNotifier()
    private val bridge = AccessibilityBridge()
    private var granted = emptySet<String>()

    private val ringer = Ringer(context, player, FakeRingNotifier(), CoroutineScope(Dispatchers.Unconfined))
    private val locator = Locator(context)
    private val locker = Locker(bridge, lockNotifier)
    private val permissions = RuntimePermissions { it in granted }
    private val commands = FindCommands(codec, ringer, locator, locker, permissions)

    private fun <T> envelope(type: String, serializer: KSerializer<T>, payload: T): Envelope =
        Envelope(type = type, requestId = "r1", payload = codec.toPayload(serializer, payload))

    private fun expectError(handler: CommandHandler, envelope: Envelope): CommandException =
        try {
            runBlocking { handler.handle(envelope) }
            fail("expected CommandException")
            error("unreachable")
        } catch (e: CommandException) {
            e
        }

    @Test
    fun `ring start plays and stop stops`() = runBlocking {
        commands.ring.handle(envelope("find.ring", FindRingRequest.serializer(), FindRingRequest("START")))
        assertEquals(1, player.plays)
        commands.ring.handle(envelope("find.ring", FindRingRequest.serializer(), FindRingRequest("STOP")))
        assertTrue(player.stops >= 1)
    }

    @Test
    fun `unknown ring action is a validation error`() {
        val error = expectError(commands.ring, envelope("find.ring", FindRingRequest.serializer(), FindRingRequest("PAUSE")))
        assertEquals(ErrorCodes.VALIDATION, error.code)
    }

    @Test
    fun `lock without the service is PERMISSION_REQUIRED accessibility`() {
        val error = expectError(commands.lock, envelope("find.lock", FindLockRequest.serializer(), FindLockRequest("Call me", null)))
        assertEquals(ErrorCodes.PERMISSION_REQUIRED, error.code)
        assertEquals(Permissions.ACCESSIBILITY, error.permission)
    }

    @Test
    fun `lock with a blank message is a validation error`() {
        bridge.register(RecordingActions())
        val error = expectError(commands.lock, envelope("find.lock", FindLockRequest.serializer(), FindLockRequest("   ", null)))
        assertEquals(ErrorCodes.VALIDATION, error.code)
    }

    @Test
    fun `lock posts the message when the service is on`() = runBlocking {
        bridge.register(RecordingActions())
        commands.lock.handle(envelope("find.lock", FindLockRequest.serializer(), FindLockRequest("Lost phone", "+8801")))
        assertEquals("Lost phone" to "+8801", lockNotifier.last)
    }

    @Test
    fun `locate without permission is PERMISSION_REQUIRED location`() {
        val error = expectError(commands.locate, Envelope(type = "find.locate", requestId = "r1"))
        assertEquals(ErrorCodes.PERMISSION_REQUIRED, error.code)
        assertEquals(Permissions.LOCATION, error.permission)
    }

    @Test
    fun `locate with permission but location off is UNSUPPORTED`() {
        granted = setOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val manager = context.getSystemService(LocationManager::class.java)
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, false)
        shadowOf(manager).setProviderEnabled(LocationManager.NETWORK_PROVIDER, false)
        shadowOf(manager).setProviderEnabled(LocationManager.PASSIVE_PROVIDER, false)

        val error = expectError(commands.locate, Envelope(type = "find.locate", requestId = "r1"))
        assertEquals(ErrorCodes.UNSUPPORTED, error.code)
    }
}
