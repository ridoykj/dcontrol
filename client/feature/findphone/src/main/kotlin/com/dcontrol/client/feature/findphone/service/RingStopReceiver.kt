package com.dcontrol.client.feature.findphone.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dcontrol.client.feature.findphone.domain.Ringer
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** Stops the ring when the user taps Stop on the notification. */
@AndroidEntryPoint
class RingStopReceiver : BroadcastReceiver() {

    @Inject
    lateinit var ringer: Ringer

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_STOP) ringer.stop()
    }

    companion object {
        const val ACTION_STOP = "com.dcontrol.client.findphone.STOP_RING"
    }
}
