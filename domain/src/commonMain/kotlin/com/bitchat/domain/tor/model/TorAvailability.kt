package com.bitchat.domain.tor.model

/**
 * Whether Tor can protect this build's traffic on this host, and if not, why.
 *
 * Constant for the process lifetime: it describes the build and the host, not whether Tor happens
 * to be on right now - that is [TorStatus].
 */
enum class TorAvailability {
    /** Tor can run here, and traffic really goes through it once it is on. */
    AVAILABLE,

    /**
     * The HTTP engine in this build ignores the SOCKS proxy - the Darwin and Curl engines both do -
     * so Tor could bootstrap to 100% and every connection would still leave the device directly.
     * Offering the switch here would promise protection the app cannot deliver.
     */
    NO_PROXY_SUPPORT,

    /** Traffic here would be proxied, but the native Arti library could not be loaded. */
    NATIVE_LIBRARY_MISSING;

    val isAvailable: Boolean get() = this == AVAILABLE
}
