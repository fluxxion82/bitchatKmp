package com.bitchat.repo.tor

import com.bitchat.client.httpEngineSupportsTorProxy
import com.bitchat.domain.tor.model.TorState
import com.bitchat.tor.TorManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Holds a send back until Tor is usable, but only while Tor is plausibly on its way up.
 *
 * Every terminal condition has to bail out immediately: this sits in front of the send path, so
 * anything that merely waits for the timeout costs the user [timeout] per message. It used to do
 * exactly that on a host with no Tor library - the state was permanently [TorState.ERROR] while
 * `return@collect` returned from the lambda rather than ending the collection, and `statusFlow` is
 * a StateFlow that never completes, so every send blocked for the full 30 seconds. [TorState.OFF]
 * is terminal for the same reason: a user who switches Tor off mid-send is answering the question.
 *
 * @return true when Tor is ready and traffic will actually be proxied.
 */
suspend fun awaitTorReady(
    torManager: TorManager?,
    timeout: Duration = 30.seconds,
    engineSupportsTorProxy: Boolean = httpEngineSupportsTorProxy,
    log: (String) -> Unit = ::println,
): Boolean {
    val manager = torManager ?: return false

    if (!engineSupportsTorProxy) {
        // A bootstrapped Arti would still not carry this request: the engine ignores the SOCKS
        // proxy. Saying "ready" here is what put "Tor is now ready" in the Pi's journal while
        // every relay connection went out direct.
        log("🔒 This build cannot route through a SOCKS proxy, proceeding without Tor")
        return false
    }

    if (manager.isProxyReady()) {
        log("🔒 Tor is already ready")
        return true
    }

    if (!manager.isAvailable) {
        log("🔒 Tor is unavailable on this host, proceeding without Tor")
        return false
    }

    val status = manager.statusFlow.value
    when (status.state) {
        TorState.OFF -> return false

        TorState.ERROR -> {
            log("❌ Tor is in ERROR state, proceeding without Tor")
            log("   Error: ${status.errorMessage}")
            return false
        }

        else -> Unit
    }

    log("🔒 Waiting for Tor to be ready...")
    log("   Current state: ${status.state}, bootstrap: ${status.bootstrapPercent}%")

    val settled = withTimeoutOrNull(timeout) {
        // Every way this can end has to be in the predicate. OFF belongs here as much as ERROR:
        // switching Tor off while a send waits is a terminal answer, and leaving it out made that
        // send sit here for the full timeout because a StateFlow never completes on its own.
        manager.statusFlow.first {
            manager.isProxyReady() || it.state == TorState.ERROR || it.state == TorState.OFF
        }
    }

    return when {
        settled == null -> {
            log("⏱️ Tor wait timeout ($timeout), proceeding without Tor")
            false
        }

        settled.state == TorState.ERROR -> {
            log("❌ Tor is in ERROR state, proceeding without Tor")
            log("   Error: ${settled.errorMessage}")
            false
        }

        settled.state == TorState.OFF -> {
            log("🔒 Tor was switched off while waiting, proceeding without Tor")
            false
        }

        else -> {
            log("✅ Tor is now ready")
            true
        }
    }
}
