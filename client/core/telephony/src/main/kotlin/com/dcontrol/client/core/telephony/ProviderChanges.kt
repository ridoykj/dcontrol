package com.dcontrol.client.core.telephony

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.debounce

/** Change notifications of a content provider, debounced so a burst of writes becomes one signal. */
fun interface ProviderChanges {
    fun changes(uri: Uri): Flow<Unit>
}

@Singleton
class AndroidProviderChanges @Inject constructor(
    @ApplicationContext private val context: Context,
) : ProviderChanges {
    override fun changes(uri: Uri): Flow<Unit> = observe(uri).debounced(DEBOUNCE)

    private fun observe(uri: Uri): Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        context.contentResolver.registerContentObserver(uri, true, observer)
        awaitClose { context.contentResolver.unregisterContentObserver(observer) }
    }.buffer(1, BufferOverflow.DROP_OLDEST)

    companion object {
        val DEBOUNCE: Duration = 1.seconds
    }
}

/** Emits once [window] after the last signal of a burst. */
@OptIn(FlowPreview::class)
fun Flow<Unit>.debounced(window: Duration): Flow<Unit> = debounce(window)
