package com.dcontrol.client.feature.connection.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dcontrol.client.core.network.credentials.CredentialStore
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Restarts the connection after a reboot or app update, if the phone is paired. */
class BootReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface BootReceiverEntryPoint {
        fun credentialStore(): CredentialStore
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val appContext = context.applicationContext
        val credentialStore = EntryPointAccessors
            .fromApplication(appContext, BootReceiverEntryPoint::class.java)
            .credentialStore()
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (credentialStore.credentials.first() != null) ConnectionService.start(appContext)
            } finally {
                pending.finish()
            }
        }
    }
}
