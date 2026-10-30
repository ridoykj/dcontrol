package com.dcontrol.client.feature.screen.domain

/**
 * Opens the MediaProjection consent screen. With the Accessibility service bound the app may start an activity
 * from the background, so the consent screen opens directly; otherwise a high-importance notification asks the
 * user to tap. The decision is made by [ScreenController]; this seam lets it be tested without Android.
 */
interface ScreenConsentFlow {
    fun launchConsent(sessionId: String)
    fun postRequest(sessionId: String, viewer: String)

    /** Stops the capture foreground service once a session has ended, whatever ended it. */
    fun stopCapture()
}
