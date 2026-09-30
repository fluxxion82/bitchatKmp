package com.bitchat.local.prefs

import com.bitchat.domain.tor.model.TorMode

/**
 * Linux (the embedded build) starts with Tor off, because its HTTP engine cannot route through a
 * SOCKS proxy at all (`httpEngineSupportsTorProxy` is false in `ktorHttpClient.linux.kt`).
 *
 * Defaulting to ON there is not a safe default but a dead one: the Tor gate refuses every relay
 * connection while the mode is ON, so a fresh install had no geohash channels and no Nostr DMs
 * because of a choice its user never made. The coupling is deliberate and repeated rather than
 * derived: `:data:local:platform` does not depend on `:data:remote:rest:client`, and a whole module
 * dependency for one boolean is worse than these two lines. Keep this in step with that actual.
 */
internal actual val platformDefaultTorMode: TorMode = TorMode.OFF
