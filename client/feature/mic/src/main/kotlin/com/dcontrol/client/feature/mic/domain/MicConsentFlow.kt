package com.dcontrol.client.feature.mic.domain

/**
 * Asks the owner to approve a microphone session. When the app is in the foreground the consent prompt opens
 * directly; otherwise a high-importance "Tap to share your microphone" notification opens it. The decision is
 * made by [MicController]; this seam lets it be tested without Android.
 */
interface MicConsentFlow {
    /** True while one of the app's activities is visible, so the prompt may open without a notification. */
    fun canLaunchDirectly(): Boolean

    fun launchConsent(sessionId: String)
    fun postRequest(sessionId: String, viewer: String)
    fun cancelRequest()

    /** Stops the capture foreground service once a session has ended, whatever ended it. */
    fun stopCapture()
}
