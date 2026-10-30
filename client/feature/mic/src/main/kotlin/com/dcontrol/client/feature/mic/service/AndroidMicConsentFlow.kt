package com.dcontrol.client.feature.mic.service

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import com.dcontrol.client.feature.mic.domain.MicConsentFlow
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** Opens the consent prompt directly while the app is visible, or posts the request notification. */
class AndroidMicConsentFlow @Inject constructor(
    @ApplicationContext private val context: Context,
) : MicConsentFlow {

    /** IMPORTANCE_FOREGROUND means an activity is visible; the connection's own service only rates lower. */
    override fun canLaunchDirectly(): Boolean {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    override fun launchConsent(sessionId: String) {
        context.startActivity(MicConsentActivity.intent(context, sessionId))
    }

    override fun postRequest(sessionId: String, viewer: String) {
        MicNotifications(context).postRequest(sessionId, viewer)
    }

    override fun cancelRequest() {
        MicNotifications(context).cancelRequest()
    }

    override fun stopCapture() {
        context.stopService(Intent(context, MicCaptureService::class.java))
    }
}
