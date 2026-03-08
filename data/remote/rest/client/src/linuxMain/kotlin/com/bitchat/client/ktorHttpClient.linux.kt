package com.bitchat.client

import com.bitchat.tor.TorManager
import io.ktor.client.engine.*
import io.ktor.client.engine.curl.*

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
    // Curl engine supports TLS on Native (CIO doesn't)
    // Use custom factory with CA path configured for Linux
    // TODO: Add SOCKS proxy support for Tor when needed
    return LinuxCurl
}
