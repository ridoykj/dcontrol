package com.dcontrol.client.feature.mic.service

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.stringResource
import com.dcontrol.client.core.designsystem.theme.DControlTheme
import com.dcontrol.client.feature.mic.R
import com.dcontrol.client.feature.mic.domain.MicController
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Translucent activity that asks the owner "Share your microphone with <viewer>?". It opens directly while the app
 * is visible, otherwise from the request notification. Only the owner's tap on Share starts the capture service;
 * dismissing the prompt or leaving it declines. It never asks for runtime permissions: without RECORD_AUDIO the
 * start was already refused.
 */
@AndroidEntryPoint
class MicConsentActivity : ComponentActivity() {

    @Inject
    lateinit var controller: MicController

    private var sessionId: String? = null
    private var decided = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MicNotifications(this).cancelRequest()
        val id = intent.getStringExtra(EXTRA_SESSION_ID)
        val pending = controller.pendingSession?.takeIf { it.sessionId == id }
        if (id == null || pending == null) {
            // A stale notification: the request was replaced, stopped or timed out.
            decided = true
            finish()
            return
        }
        sessionId = id
        setContent {
            DControlTheme {
                AlertDialog(
                    onDismissRequest = ::deny,
                    title = { Text(stringResource(R.string.mic_consent_title, pending.viewer)) },
                    text = { Text(stringResource(R.string.mic_consent_text, pending.viewer)) },
                    confirmButton = { TextButton(onClick = ::allow) { Text(stringResource(R.string.mic_consent_allow)) } },
                    dismissButton = { TextButton(onClick = ::deny) { Text(stringResource(R.string.mic_consent_deny)) } },
                )
            }
        }
    }

    override fun onDestroy() {
        // Leaving the prompt without choosing (home, recents; noHistory finishes it) declines the request.
        if (!decided && isFinishing && !isChangingConfigurations) sessionId?.let(controller::onConsentDenied)
        super.onDestroy()
    }

    private fun allow() {
        if (decided) return
        decided = true
        // Re-check: the request may have been stopped or replaced while the prompt was showing.
        val id = sessionId
        if (id != null && controller.pendingSession?.sessionId == id) MicCaptureService.start(this, id)
        finish()
    }

    private fun deny() {
        if (decided) return
        decided = true
        sessionId?.let(controller::onConsentDenied)
        finish()
    }

    companion object {
        const val EXTRA_SESSION_ID = "com.dcontrol.client.mic.SESSION_ID"

        fun intent(context: Context, sessionId: String): Intent =
            Intent(context, MicConsentActivity::class.java)
                .putExtra(EXTRA_SESSION_ID, sessionId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
