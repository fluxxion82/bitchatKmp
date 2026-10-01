@file:OptIn(ExperimentalForeignApi::class)

package com.bitchat.client

import com.bitchat.client.harness.Outcome
import com.bitchat.client.harness.PosixNet
import com.bitchat.client.harness.SocksCaptureServer
import com.bitchat.client.harness.SocksMode
import com.bitchat.client.harness.TunnelHttpReply
import com.bitchat.client.harness.TorOffIntent
import com.bitchat.client.harness.TorOnIntent
import com.bitchat.client.harness.assertOnlyDomainConnects
import com.bitchat.client.harness.attempt
import com.bitchat.client.harness.epochMillis
import com.bitchat.client.harness.harnessProvider
import com.bitchat.client.harness.openWebSocket
import com.bitchat.client.model.ClientType
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.Foundation.NSUUID
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The plan's second detector (B2.3/B3.1): probes PUBLICLY delegated names that resolve to
 * documentation addresses, so a packet capture on the physical interface can show any plaintext DNS
 * query carrying the uuid and any SYN to the unroutable destination. OPT-IN only:
 *
 *     ./gradlew -Pbitchat.publicLeakProbe=true :data:remote:rest:client:macosArm64Test --console=plain
 *
 * Without the property every case prints one "disabled" line and returns; nothing touches the
 * network. With it, each case prints `PROBE <case> <hostname> <start epoch ms> <end epoch ms>` for
 * matching against the capture. The test asserts only what it can see itself (the SOCKS capture);
 * the DNS/SYN verdict for the Tor-ON cases comes from the pcap.
 *
 * Names: `<uuid>.203-0-113-7.sslip.io` -> A 203.0.113.7 (TEST-NET-3) and
 * `<uuid>.2001-db8--7.sslip.io` -> AAAA 2001:db8::7 (documentation prefix); both verified with dig
 * and dscacheutil on 2026-09-30.
 */
class DarwinPublicLeakProbeTest {

    /** DIRECT (intent OFF): expect a DNS query for the name and a SYN to 203.0.113.7:443 in the pcap. */
    @Test
    fun positiveControlHttpsDirect() = probe("positive-control-https") {
        val name = v4Name()
        val outcome = probed(name) {
            attempt(DIRECT_BOUND) {
                provider(TorOffIntent).withRestClient(ClientType.NOSTR, emptyList()) { client -> client.get("https://$name/") }
            }
        }
        assertEquals(0, socks.accepted(), "a direct request must not touch the SOCKS capture: ${socks.describe()}")
        println("[$case] direct HTTPS to $name: $outcome")
    }

    /** DIRECT WSS: same expectation as the HTTPS control. */
    @Test
    fun positiveControlWssDirect() = probe("positive-control-wss") {
        val name = v4Name()
        val outcome = probed(name) { attempt(DIRECT_BOUND) { openWebSocket(provider(TorOffIntent), "wss://$name/") } }
        assertEquals(0, socks.accepted(), "a direct WebSocket must not touch the SOCKS capture: ${socks.describe()}")
        println("[$case] direct WSS to $name: $outcome")
    }

    /** DIRECT HTTPS to the v6 name: expect an AAAA query, and a SYN to 2001:db8::7 when the host has a v6 route. */
    @Test
    fun positiveControlV6Direct() = probe("positive-control-v6") {
        val name = v6Name()
        val outcome = probed(name) {
            attempt(DIRECT_BOUND) {
                provider(TorOffIntent).withRestClient(ClientType.NOSTR, emptyList()) { client -> client.get("https://$name/") }
            }
        }
        assertEquals(0, socks.accepted(), "a direct request must not touch the SOCKS capture: ${socks.describe()}")
        println("[$case] direct HTTPS to $name: $outcome")
    }

    @Test
    fun socksHttps() = probe("socks-https", SocksMode.Close) {
        val name = v4Name()
        val outcome = probed(name) {
            attempt {
                provider(TorOnIntent).withRestClient(ClientType.NOSTR, emptyList()) { client -> client.get("https://$name/") }
            }
        }
        assertTrue(socks.awaitConnects(1, 10.seconds), "no CONNECT reached SOCKS; $outcome ${socks.describe()}")
        settle()
        assertOnlyDomainConnects(listOf(name), 443)
        assertNotNull(outcome.error, "a request through a failing SOCKS proxy completed: something bypassed it; ${socks.describe()}")
    }

    @Test
    fun socksHttpsV6Name() = probe("socks-https-v6", SocksMode.Close) {
        val name = v6Name()
        val outcome = probed(name) {
            attempt {
                provider(TorOnIntent).withRestClient(ClientType.NOSTR, emptyList()) { client -> client.get("https://$name/") }
            }
        }
        assertTrue(socks.awaitConnects(1, 10.seconds), "no CONNECT reached SOCKS; $outcome ${socks.describe()}")
        settle()
        assertOnlyDomainConnects(listOf(name), 443)
        assertNotNull(outcome.error, "a request through a failing SOCKS proxy completed: something bypassed it; ${socks.describe()}")
    }

    @Test
    fun socksWss() = probe("socks-wss", SocksMode.Close) {
        val name = v4Name()
        val outcome = probed(name) { attempt { openWebSocket(provider(TorOnIntent), "wss://$name/") } }
        assertTrue(socks.awaitConnects(1, 10.seconds), "no CONNECT reached SOCKS; $outcome ${socks.describe()}")
        settle()
        assertOnlyDomainConnects(listOf(name), 443)
        assertNotNull(outcome.error, "a WebSocket through a failing SOCKS proxy opened: something bypassed it; ${socks.describe()}")
    }

    @Test
    fun socksRefuse() = probe("socks-refuse", SocksMode.Refuse) {
        val name = v4Name()
        val outcome = probed(name) {
            attempt {
                provider(TorOnIntent).withRestClient(ClientType.NOSTR, emptyList()) { client -> client.get("https://$name/") }
            }
        }
        assertTrue(socks.awaitConnects(1, 10.seconds), "no CONNECT reached SOCKS; $outcome ${socks.describe()}")
        settle()
        assertNotNull(outcome.error, "a request the SOCKS proxy refused completed: something bypassed it; ${socks.describe()}")
        assertTrue(outcome.elapsed < FAILURE_BOUND, "refused SOCKS took ${outcome.elapsed}, bound is $FAILURE_BOUND")
        assertOnlyDomainConnects(listOf(name), 443, minimum = 1)
    }

    @Test
    fun socksDeadPort() = probe("socks-dead-port", SocksMode.Close) {
        val name = v4Name()
        val deadPort = deadPort()
        val outcome = probed(name) {
            attempt {
                provider(TorOnIntent, socksPort = deadPort).withRestClient(ClientType.NOSTR, emptyList()) { client ->
                    client.get("https://$name/")
                }
            }
        }
        settle()
        assertNotNull(outcome.error, "a request to a dead SOCKS port completed: something bypassed it; ${socks.describe()}")
        assertTrue(outcome.elapsed < FAILURE_BOUND, "dead SOCKS port took ${outcome.elapsed}, bound is $FAILURE_BOUND")
        assertEquals(0, socks.accepted(), "the live capture port must stay untouched: ${socks.describe()}")
    }

    @Test
    fun socksWssReconnect() = probe("socks-wss-reconnect", SocksMode.Close) {
        val name = v4Name()
        val provider = provider(TorOnIntent)
        var first: Outcome? = null
        val second = probed(name) {
            first = attempt { openWebSocket(provider, "wss://$name/") }
            attempt { openWebSocket(provider, "wss://$name/") }
        }
        assertTrue(socks.awaitConnects(2, 10.seconds), "reconnect never reached SOCKS; $first $second ${socks.describe()}")
        settle()
        assertNotNull(first?.error, "first WebSocket attempt opened through a closing proxy; ${socks.describe()}")
        assertNotNull(second.error, "reconnect opened through a closing proxy; ${socks.describe()}")
        assertOnlyDomainConnects(listOf(name, name), 443, minimum = 2)
    }

    /** Two public names must both remain domain-form SOCKS CONNECTs across a redirect. */
    @Test
    fun socksHttpRedirect() {
        val first = newV4Name()
        val second = newV4Name()
        probe("socks-http-redirect", SocksMode.ServeHttp { request ->
            if (request.host.equals(first, ignoreCase = true)) TunnelHttpReply.redirect("http://$second/") else TunnelHttpReply.ok("ok")
        }) {
            var body = ""
            val outcome = probed(first, second) {
                attempt {
                    provider(TorOnIntent).withRestClient(ClientType.NOSTR, emptyList()) { client ->
                        body = client.get("http://$first/").bodyAsText()
                    }
                }
            }
            assertNull(outcome.error, "redirect via SOCKS failed: ${socks.describe()}")
            assertEquals("ok", body)
            assertOnlyDomainConnects(listOf(first, second), 80)
        }
    }

    // ---- harness -----------------------------------------------------------------------------

    private class ProbeRun(val case: String, val socks: SocksCaptureServer) {
        fun provider(intent: com.bitchat.domain.tor.RequestedTorIntent, socksPort: Int = socks.port) =
            harnessProvider(intent, socksPort)

        fun v4Name(): String = "${uuid()}.$V4_SUFFIX"

        fun v6Name(): String = "${uuid()}.$V6_SUFFIX"

        /** Prints the PROBE line that a packet capture is matched against. */
        suspend fun probed(vararg names: String, block: suspend () -> Outcome): Outcome {
            val start = epochMillis()
            try {
                return block()
            } finally {
                settle()
                names.forEach { name -> println("PROBE $case $name $start ${epochMillis()}") }
            }
        }

        suspend fun attempt(bound: kotlin.time.Duration = com.bitchat.client.harness.REQUEST_BOUND, block: suspend () -> Unit): Outcome =
            attempt(case, bound, block)

        suspend fun settle() = delay(SETTLE)

        fun assertOnlyDomainConnects(expectedHosts: List<String>, port: Int, minimum: Int = expectedHosts.size) =
            socks.assertOnlyDomainConnects(case, expectedHosts, port, minimum)

        private fun uuid(): String = NSUUID().UUIDString.lowercase()
    }

    private fun probe(case: String, mode: SocksMode = SocksMode.Close, block: suspend ProbeRun.() -> Unit) = runBlocking {
        val flag = getenv(ENABLE_VARIABLE)?.toKString()
        if (flag != "true") {
            println("PUBLIC LEAK PROBE disabled for [$case]: $ENABLE_VARIABLE=${flag ?: "<unset>"}; enable with -Pbitchat.publicLeakProbe=true; SKIPPED, not a probe")
            return@runBlocking
        }
        println("PUBLIC LEAK PROBE enabled for [$case]: $ENABLE_VARIABLE=$flag reached the test process")
        PosixNet.ignoreSigpipe()
        val socks = SocksCaptureServer(mode)
        val run = ProbeRun(case, socks)
        println("[$case] start socks=127.0.0.1:${socks.port}")
        try {
            withTimeout(CASE_BOUND) { run.block() }
        } finally {
            socks.stop()
            println("[$case] end ${socks.describe()}")
        }
    }

    private fun deadPort(): Int {
        val listener = PosixNet.tcpListener()
        val port = PosixNet.boundPort(listener)
        PosixNet.closeFd(listener)
        return port
    }

    private fun newV4Name(): String = "${NSUUID().UUIDString.lowercase()}.$V4_SUFFIX"

    private companion object {
        const val ENABLE_VARIABLE = "BITCHAT_PUBLIC_LEAK_PROBE"
        const val V4_SUFFIX = "203-0-113-7.sslip.io"
        const val V6_SUFFIX = "2001-db8--7.sslip.io"
        val CASE_BOUND = 40.seconds
        /** Direct connects to unroutable addresses only time out; keep that short. */
        val DIRECT_BOUND = 5.seconds
        val FAILURE_BOUND = 15.seconds
        val SETTLE = 750.milliseconds
    }
}
