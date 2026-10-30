package com.dcontrol.client.core.rtc

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hands the microphone to one session at a time (ws-v1.md, Phase 6: "The microphone has one user"). The remote
 * microphone refuses to start while a camera or screen session holds it; those start without audio while the
 * microphone feature holds it. A holder is taken when a request with audio is accepted and released when that
 * request or its session ends, whatever ended it.
 */
@Singleton
class MicrophoneLock @Inject constructor() {

    private var current: Hold? = null

    /** The label of the current holder (`camera`, `screen`, `mic`), or null when the microphone is free. */
    val owner: String?
        @Synchronized get() = current?.owner

    /** Takes the microphone for [owner], or returns null when someone holds it already. */
    @Synchronized
    fun tryAcquire(owner: String): Hold? {
        if (current != null) return null
        return Hold(owner).also { current = it }
    }

    @Synchronized
    private fun release(hold: Hold) {
        if (current === hold) current = null
    }

    /** One holder's claim. [release] is idempotent and never frees a later holder's claim. */
    inner class Hold internal constructor(val owner: String) {
        fun release() = this@MicrophoneLock.release(this)
    }

    companion object {
        const val CAMERA = "camera"
        const val SCREEN = "screen"
        const val MIC = "mic"
    }
}
