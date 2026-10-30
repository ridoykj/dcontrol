package com.dcontrol.client.core.accessibility

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The seam between the Accessibility service and the features that drive it. The service registers its
 * [ControlActions] when the system binds it and clears them when it disconnects; features (screen mirroring,
 * find my phone) depend only on this singleton, never on the service module.
 */
@Singleton
class AccessibilityBridge @Inject constructor() {

    private val _available = MutableStateFlow(false)

    /** True while the Accessibility service is bound and can inject input. */
    val available: StateFlow<Boolean> = _available.asStateFlow()

    @Volatile
    var actions: ControlActions? = null
        private set

    val isBound: Boolean get() = actions != null

    fun register(actions: ControlActions) {
        this.actions = actions
        _available.value = true
    }

    fun unregister(actions: ControlActions) {
        if (this.actions === actions) {
            this.actions = null
            _available.value = false
        }
    }
}
