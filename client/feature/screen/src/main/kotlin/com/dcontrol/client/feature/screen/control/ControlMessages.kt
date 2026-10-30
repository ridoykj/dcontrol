package com.dcontrol.client.feature.screen.control

import com.dcontrol.client.core.accessibility.ControlActions
import com.dcontrol.client.core.accessibility.EditKey
import com.dcontrol.client.core.accessibility.NavKey
import com.dcontrol.client.core.websocket.protocol.Phase4Limits
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A validated browser -> phone control message. */
sealed interface ControlMessage {
    data class Down(val x: Float, val y: Float) : ControlMessage
    data class Move(val x: Float, val y: Float) : ControlMessage
    data class Up(val x: Float, val y: Float) : ControlMessage
    data class Wheel(val x: Float, val y: Float, val dy: Float) : ControlMessage
    data class Key(val key: NavKey) : ControlMessage
    data class Text(val value: String) : ControlMessage
    data class Edit(val key: EditKey) : ControlMessage
}

/**
 * Parses the control channel's JSON, returning null for anything invalid: an unknown `t`, out-of-range
 * coordinates, an oversized or empty text, or a malformed body. The caller counts the nulls as drops.
 */
class ControlMessageParser(private val json: Json = Json { ignoreUnknownKeys = true }) {

    fun parse(text: String): ControlMessage? {
        val obj = try {
            json.parseToJsonElement(text).jsonObject
        } catch (_: Exception) {
            return null
        }
        return when (obj.string("t")) {
            "down" -> point(obj)?.let { (x, y) -> ControlMessage.Down(x, y) }
            "move" -> point(obj)?.let { (x, y) -> ControlMessage.Move(x, y) }
            "up" -> point(obj)?.let { (x, y) -> ControlMessage.Up(x, y) }
            "wheel" -> wheel(obj)
            "key" -> navKey(obj.string("k"))?.let { ControlMessage.Key(it) }
            "text" -> textOf(obj)
            "edit" -> editKey(obj.string("k"))?.let { ControlMessage.Edit(it) }
            else -> null
        }
    }

    private fun point(obj: JsonObject): Pair<Float, Float>? {
        val x = obj.fraction("x") ?: return null
        val y = obj.fraction("y") ?: return null
        return x to y
    }

    private fun wheel(obj: JsonObject): ControlMessage.Wheel? {
        val x = obj.fraction("x") ?: return null
        val y = obj.fraction("y") ?: return null
        val dy = obj["dy"]?.jsonPrimitive?.floatOrNull ?: return null
        if (dy < -MAX_WHEEL || dy > MAX_WHEEL) return null
        return ControlMessage.Wheel(x, y, dy)
    }

    private fun textOf(obj: JsonObject): ControlMessage.Text? {
        val value = obj.string("s") ?: return null
        if (value.isEmpty() || value.length > Phase4Limits.MAX_CONTROL_TEXT) return null
        return ControlMessage.Text(value)
    }

    private fun navKey(key: String?): NavKey? = when (key) {
        "back" -> NavKey.BACK
        "home" -> NavKey.HOME
        "recents" -> NavKey.RECENTS
        "notifications" -> NavKey.NOTIFICATIONS
        "power" -> NavKey.POWER
        "lock" -> NavKey.LOCK
        else -> null
    }

    private fun editKey(key: String?): EditKey? = when (key) {
        "backspace" -> EditKey.BACKSPACE
        "enter" -> EditKey.ENTER
        else -> null
    }

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.let { if (it.isString) it.content else null }

    /** A coordinate that must be a 0..1 fraction. */
    private fun JsonObject.fraction(key: String): Float? {
        val value = this[key]?.jsonPrimitive?.floatOrNull ?: return null
        return value.takeIf { it in 0f..1f }
    }

    private companion object {
        const val MAX_WHEEL = 2000f
    }
}

/**
 * Applies parsed messages to [ControlActions] and counts drops. After
 * [Phase4Limits.MAX_CONTROL_DROPS] invalid messages it calls [onExcessiveDrops] so the session closes the
 * channel. Messages that arrive while the service is unbound are ignored, not dropped.
 */
class ControlRouter(
    private val actions: () -> ControlActions?,
    private val onExcessiveDrops: () -> Unit,
    private val parser: ControlMessageParser = ControlMessageParser(),
) {
    var drops = 0
        private set

    fun handle(text: String) {
        if (text.length > Phase4Limits.MAX_CONTROL_MESSAGE) return drop()
        val message = parser.parse(text) ?: return drop()
        val target = actions() ?: return
        apply(target, message)
    }

    private fun apply(target: ControlActions, message: ControlMessage) = when (message) {
        is ControlMessage.Down -> target.down(message.x, message.y)
        is ControlMessage.Move -> target.move(message.x, message.y)
        is ControlMessage.Up -> target.up(message.x, message.y)
        is ControlMessage.Wheel -> target.wheel(message.x, message.y, message.dy)
        is ControlMessage.Key -> { target.navKey(message.key); Unit }
        is ControlMessage.Text -> { target.text(message.value); Unit }
        is ControlMessage.Edit -> { target.edit(message.key); Unit }
    }

    private fun drop() {
        if (++drops >= Phase4Limits.MAX_CONTROL_DROPS) onExcessiveDrops()
    }
}

/** Phone -> browser control messages. */
object PhoneControl {
    fun size(width: Int, height: Int, rotation: Int): String =
        """{"t":"size","w":$width,"h":$height,"rotation":$rotation}"""

    fun control(available: Boolean): String = """{"t":"control","available":$available}"""
}
