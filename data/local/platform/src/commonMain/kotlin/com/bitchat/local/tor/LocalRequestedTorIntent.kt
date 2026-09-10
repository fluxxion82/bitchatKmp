package com.bitchat.local.tor

import com.bitchat.domain.tor.MutableRequestedTorIntent
import com.bitchat.domain.tor.model.TorMode
import com.bitchat.local.prefs.TorPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds the requested Tor intent, backed by preferences.
 *
 * Constructed eagerly and seeded from storage in its initialiser, so the value is correct the first
 * time anything asks. Its dependencies stop at preferences on purpose: pulling in a repository or
 * anything that builds a network client would make this unavailable at the moment routing needs it,
 * and would risk a dependency cycle.
 */
internal class LocalRequestedTorIntent(
    private val torPreferences: TorPreferences,
) : MutableRequestedTorIntent {

    private val _updates = MutableStateFlow(torPreferences.getTorMode())

    override val updates: StateFlow<TorMode> = _updates.asStateFlow()

    /**
     * Read straight through to storage rather than served from [_updates].
     *
     * TorRepo still writes the preference directly in a few places -- a successful start, a
     * disable, an explicit set, a data wipe. Until those are routed through [set], a cached value
     * here could disagree with what was actually stored, and the whole point of this type is to be
     * the answer routing trusts. Reading through cannot go stale.
     */
    override val current: TorMode get() = torPreferences.getTorMode()

    override fun set(mode: TorMode) {
        torPreferences.setTorMode(mode)
        _updates.value = mode
    }
}
