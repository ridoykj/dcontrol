package com.dcontrol.client.feature.connection.data

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import com.dcontrol.client.core.websocket.protocol.Battery
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/** Reads the sticky ACTION_BATTERY_CHANGED broadcast; needs no permission. */
@Singleton
class BatteryReader @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun read(): Battery {
        val intent = ContextCompat.registerReceiver(
            context,
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        ) ?: return Battery(level = 0, charging = false)
        return fromIntent(intent)
    }

    internal fun fromIntent(intent: Intent): Battery {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val percent = if (level >= 0 && scale > 0) (level * 100f / scale).roundToInt().coerceIn(0, 100) else 0
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return Battery(level = percent, charging = charging)
    }
}
