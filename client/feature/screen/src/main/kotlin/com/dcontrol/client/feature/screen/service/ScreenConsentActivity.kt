package com.dcontrol.client.feature.screen.service

import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.dcontrol.client.feature.screen.domain.ScreenController
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Translucent, no-UI activity that shows the system's MediaProjection consent dialog. It is opened directly
 * when the Accessibility service is bound (the background-activity-launch exemption), otherwise from the
 * high-importance request notification. dcontrol never auto-taps Start; only the user can.
 */
@AndroidEntryPoint
class ScreenConsentActivity : ComponentActivity() {

    @Inject
    lateinit var controller: ScreenController

    private val consent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            ScreenCaptureService.start(this, data)
        } else {
            controller.onConsentDenied()
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ScreenNotifications(this).cancelRequest()
        if (savedInstanceState != null) {
            finish()
            return
        }
        val manager = getSystemService(MediaProjectionManager::class.java)
        if (manager == null) {
            controller.onConsentDenied()
            finish()
            return
        }
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            manager.createScreenCaptureIntent()
        }
        consent.launch(intent)
    }

    companion object {
        const val EXTRA_SESSION_ID = "com.dcontrol.client.screen.SESSION_ID"

        fun intent(context: android.content.Context, sessionId: String): Intent =
            Intent(context, ScreenConsentActivity::class.java)
                .putExtra(EXTRA_SESSION_ID, sessionId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
