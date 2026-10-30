package com.dcontrol.client.feature.screen.service

import android.content.Context
import android.content.Intent
import com.dcontrol.client.feature.screen.domain.ScreenConsentFlow
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** Opens the consent activity directly, or posts the request notification when it can't. */
class AndroidScreenConsentFlow @Inject constructor(
    @ApplicationContext private val context: Context,
) : ScreenConsentFlow {

    override fun launchConsent(sessionId: String) {
        context.startActivity(ScreenConsentActivity.intent(context, sessionId))
    }

    override fun postRequest(sessionId: String, viewer: String) {
        ScreenNotifications(context).postRequest(sessionId, viewer)
    }

    override fun stopCapture() {
        context.stopService(Intent(context, ScreenCaptureService::class.java))
    }
}
