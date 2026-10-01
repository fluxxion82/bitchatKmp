package com.bitchat.client

import com.bitchat.tor.TorManager
import io.ktor.client.engine.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

actual fun getEngine(isDebug: Boolean, torManager: TorManager?): HttpClientEngineFactory<*> {
    // Every routed client owns a route-derived NSURLSession. Its configuration applies SOCKS
    // before the session exists and closeAndJoin awaits invalidateAndCancel on that same session.
    return OwnedSessionEngineFactory()
}

/** The owned Darwin session applies SOCKS before it is created and retires it before route publication. */
actual val httpEngineSupportsTorProxy: Boolean = true

/** The Darwin engine labels PONG correctly, so pings keep an idle relay session alive. */
actual val websocketPingInterval: Duration? = 30.seconds
