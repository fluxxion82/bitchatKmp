package com.bitchat.local.prefs

import com.bitchat.domain.tor.model.TorMode

/**
 * iOS starts with Tor off, for the same reason as Linux: the Darwin HTTP engine cannot route
 * through a SOCKS proxy (`httpEngineSupportsTorProxy` is false in `ktorHttpClient.apple.kt`), so an
 * ON default only blocks every relay and leaves the user nothing to connect with. See
 * `PlatformTorDefault.linux.kt` for why the two are kept in step by hand.
 */
internal actual val platformDefaultTorMode: TorMode = TorMode.OFF
