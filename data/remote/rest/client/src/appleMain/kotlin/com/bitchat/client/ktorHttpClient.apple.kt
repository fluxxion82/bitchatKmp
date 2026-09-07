package com.bitchat.client

import com.bitchat.tor.TorManager
import io.ktor.client.engine.*
import io.ktor.client.engine.darwin.*

actual fun getEngine(isDebug: Boolean, torManager: TorManager?): HttpClientEngineFactory<*> {
    // TODO: Darwin engine SOCKS proxy support needs investigation
    // For now, return plain Darwin engine
    return Darwin
}

/** The Darwin engine has no SOCKS proxy support here, so nothing ever leaves via Tor. */
actual val httpEngineSupportsTorProxy: Boolean = false
