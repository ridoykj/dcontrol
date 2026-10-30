package com.dcontrol.client.core.websocket

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

/**
 * Work that only runs while the device socket is online, such as content observers that send `*.changed`
 * events. Feature modules contribute it with `@IntoSet`; [watch] is cancelled when the socket goes offline
 * and called again on the next `hello.ack`.
 */
fun interface OnlineWatcher {
    suspend fun watch()
}

/** Runs every watcher while [state] is [ConnectionState.Online]; a failing watcher doesn't stop the others. */
fun CoroutineScope.launchOnlineWatchers(state: Flow<ConnectionState>, watchers: Set<OnlineWatcher>): Job = launch {
    state.map { it is ConnectionState.Online }.distinctUntilChanged().collectLatest { online ->
        if (!online) return@collectLatest
        supervisorScope {
            watchers.forEach { watcher ->
                launch {
                    try {
                        watcher.watch()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // Best effort: the web re-syncs with the list commands.
                    }
                }
            }
        }
    }
}
