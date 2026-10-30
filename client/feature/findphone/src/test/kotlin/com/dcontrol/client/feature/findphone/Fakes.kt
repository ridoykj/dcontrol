package com.dcontrol.client.feature.findphone

import com.dcontrol.client.core.accessibility.ControlActions
import com.dcontrol.client.core.accessibility.EditKey
import com.dcontrol.client.core.accessibility.NavKey
import com.dcontrol.client.feature.findphone.domain.LockNotifier
import com.dcontrol.client.feature.findphone.domain.RingNotifier
import com.dcontrol.client.feature.findphone.domain.TonePlayer

class FakeTonePlayer : TonePlayer {
    var plays = 0
    var stops = 0
    override fun play() { plays++ }
    override fun stop() { stops++ }
}

class FakeRingNotifier : RingNotifier {
    var shown = 0
    var cancelled = 0
    override fun show() { shown++ }
    override fun cancel() { cancelled++ }
}

class FakeLockNotifier : LockNotifier {
    var last: Pair<String, String?>? = null
    override fun show(message: String, contact: String?) { last = message to contact }
}

class RecordingActions : ControlActions {
    val navKeys = mutableListOf<NavKey>()
    override fun down(x: Float, y: Float) = Unit
    override fun move(x: Float, y: Float) = Unit
    override fun up(x: Float, y: Float) = Unit
    override fun wheel(x: Float, y: Float, dy: Float) = Unit
    override fun navKey(key: NavKey): Boolean { navKeys += key; return true }
    override fun text(value: String) = true
    override fun edit(key: EditKey) = true
}
