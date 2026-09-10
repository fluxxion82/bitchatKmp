package com.bitchat.local.prefs

import com.bitchat.domain.tor.model.TorMode

/**
 * What Tor mode a fresh install starts from, when the preference has never been written.
 *
 * Per-platform because the honest answer differs. It applies only to an absent key: an explicitly
 * stored choice is always preserved.
 */
internal expect val platformDefaultTorMode: TorMode
