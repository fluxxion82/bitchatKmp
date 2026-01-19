package com.bitchat.local.util

/**
 * CoreLocation on macOS requires a running runloop on the main thread.
 * Since we're operating in a shared library context loaded by JVM,
 * we just need to ensure that the macOS main dispatch queue is active.
 *
 * For now, this is a no-op placeholder - CoreLocation should work
 * as long as the JVM's main thread has an event loop running (which
 * Compose Desktop does via Swing/AWT).
 */
object CoreLocationRunLoop {
    fun ensure() {
        // No-op for now - CoreLocation manager will dispatch to main queue
        // which should be running in the context of the JVM app's event loop
    }
}
