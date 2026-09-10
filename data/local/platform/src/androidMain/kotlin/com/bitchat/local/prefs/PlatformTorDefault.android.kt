package com.bitchat.local.prefs

import com.bitchat.domain.tor.model.TorMode

/** Unchanged: Tor works on Android, so a fresh install is protected by default. */
internal actual val platformDefaultTorMode: TorMode = TorMode.ON
