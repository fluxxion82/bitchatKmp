package com.bitchat.tor

/**
 * Native Arti logging includes both lifecycle failures and failures for one client stream.
 *
 * A rejected destination stream does not stop the already-bound SOCKS listener or invalidate the
 * Tor client. Only failures that prevent the client or listener from operating should change the
 * application-wide Tor state to ERROR.
 */
internal fun isFatalTorLifecycleError(line: String): Boolean = FATAL_LIFECYCLE_ERROR_MARKERS.any {
    line.contains(it, ignoreCase = true)
}

private val FATAL_LIFECYCLE_ERROR_MARKERS = listOf(
    "data_dir is null",
    "Failed to convert data_dir",
    "Failed to create Tokio runtime",
    "Tokio runtime not initialized",
    "Failed to initialize Arti",
    "Arti client not initialized",
    "Failed to bind SOCKS proxy",
    "Failed to accept SOCKS connection",
)
