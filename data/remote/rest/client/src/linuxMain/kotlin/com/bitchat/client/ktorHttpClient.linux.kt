package com.bitchat.client

import com.bitchat.tor.TorManager
import com.bitchat.client.curl.Curl
import com.bitchat.client.curl.CurlClientEngineConfig
import io.ktor.client.engine.*
import kotlin.time.Duration

/**
 * Custom Curl engine factory that configures CA certificates for Linux.
 *
 * Ktor 3.3.3 doesn't automatically set the CA path on linuxArm64 (fixed in 3.4.0).
 * We set it explicitly to the standard Debian/Raspbian CA bundle location.
 */
private object LinuxCurl : HttpClientEngineFactory<CurlClientEngineConfig> {
    override fun create(block: CurlClientEngineConfig.() -> Unit): HttpClientEngine {
        return Curl.create {
            // Set CA certificate path for SSL verification on Linux
            // Standard location on Debian/Raspbian/Ubuntu
            caInfo = "/etc/ssl/certs/ca-certificates.crt"
            block()
        }
    }
}

actual fun getEngine(isDebug: Boolean, torManager: TorManager?): HttpClientEngineFactory<*> {
    // Curl engine supports TLS on Native (CIO doesn't). LinuxCurl is an in-repo fork of Ktor
    // Curl 3.3.3 that clears CURLOPT_NOPROXY on every easy handle.
    return LinuxCurl
}

// LinuxCurl routes through Arti with socks5h:// (remote DNS) and a forced empty CURLOPT_NOPROXY,
// so NO_PROXY in the environment cannot send a Tor request direct.
actual val httpEngineSupportsTorProxy: Boolean = true

/** No pings: the Curl engine calls an incoming PONG a Ping, so ours are never answered. */
actual val websocketPingInterval: Duration? = null
