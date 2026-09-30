package com.bitchat.local.prefs

import com.bitchat.domain.tor.model.TorMode

/**
 * Linux (the embedded build) starts with Tor on, like Android: its Curl engine routes Nostr through
 * Arti (`httpEngineSupportsTorProxy` is true in `ktorHttpClient.linux.kt`).
 *
 * The two values must move together. With Tor ON and an engine that cannot proxy, the Tor gate
 * refuses every relay connection, so a fresh install would get no geohash channels and no Nostr
 * DMs from a choice its user never made. The coupling is repeated rather than derived:
 * `:data:local:platform` does not depend on `:data:remote:rest:client`, and a module dependency for
 * one boolean is worse than keeping these two lines in step.
 */
internal actual val platformDefaultTorMode: TorMode = TorMode.ON
