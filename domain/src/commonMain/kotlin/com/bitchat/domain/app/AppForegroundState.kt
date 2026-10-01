package com.bitchat.domain.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The process foreground state as last reported by the platform lifecycle bridge.
 *
 * It deliberately starts false. Until iOS has synchronously sampled UIApplication on the main
 * thread, starting Tor would be a background start; remaining stopped is the fail-closed choice.
 */
class AppForegroundState(initiallyForeground: Boolean = false) {
    private val mutableForeground = MutableStateFlow(initiallyForeground)
    private val listeners = mutableListOf<(Boolean) -> Unit>()
    val foreground: StateFlow<Boolean> = mutableForeground.asStateFlow()

    /**
     * Adds a non-suspending foreground listener and immediately supplies the current value.
     *
     * A state flow is intentionally conflated, but an Apple background edge must still reach the
     * lifecycle coordinator while its previous start or stop is suspended. Listeners therefore
     * enqueue that edge; they must return immediately.
     */
    fun listen(listener: (Boolean) -> Unit) {
        listeners += listener
        listener(mutableForeground.value)
    }

    /** Conflated publication that is safe to call directly from a platform notification. */
    fun publish(isForeground: Boolean) {
        mutableForeground.value = isForeground
        listeners.forEach { it(isForeground) }
    }
}
