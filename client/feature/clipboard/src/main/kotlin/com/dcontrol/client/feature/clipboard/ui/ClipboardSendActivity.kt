package com.dcontrol.client.feature.clipboard.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.dcontrol.client.feature.clipboard.data.Clipboard
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Invisible activity started by the Quick Settings tile and the notification action. Android only lets
 * the focused app read the clipboard, so it waits for window focus, sends the clip and finishes.
 */
@AndroidEntryPoint
class ClipboardSendActivity : ComponentActivity() {

    @Inject
    lateinit var clipboard: Clipboard

    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handled = savedInstanceState?.getBoolean(KEY_HANDLED) ?: false
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || handled) return
        handled = true
        Toast.makeText(this, getString(clipboard.sendCurrentClip().messageRes()), Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_HANDLED, handled)
    }

    private companion object {
        const val KEY_HANDLED = "handled"
    }
}
