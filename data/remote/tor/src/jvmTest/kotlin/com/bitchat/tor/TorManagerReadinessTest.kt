package com.bitchat.tor

import com.bitchat.domain.tor.model.TorMode
import com.bitchat.domain.tor.model.TorState
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The desktop condition, exactly: the Arti wrapper answers its JNI entry points but never delivers
 * a single log line, because `nativeSetLogCallback` in `arti-desktop-wrapper/src/lib.rs` throws the
 * callback away.
 *
 * Readiness used to be reachable only from [TorManager.handleLogLine], so on JVM desktop
 * `isProxyReady()` was false forever and the OkHttp `ProxySelector` sent every request direct - Tor
 * ran and proxied nothing. These tests drive [ArtiNative] directly and never invoke the callback,
 * so they fail again if readiness ever goes back to depending on parsed log text.
 */
class TorManagerReadinessTest {

    /**
     * Stands in for the JNI bridge with the wrapper's documented return codes. Deliberately silent:
     * [registeredCallback] is captured but never called, which is what the desktop library does.
     */
    private class FakeArti(
        override val isAvailable: Boolean = true,
        private val initializeResult: Int = 0,
        private val startResult: Int = 0,
    ) : ArtiNative {
        var registeredCallback: TorManager.LogCallback? = null
        var initializeCalls = 0
        var startCalls = 0
        var stopCalls = 0

        override fun setLogCallback(callback: TorManager.LogCallback) {
            registeredCallback = callback
        }

        override fun initialize(dataDir: String): Int {
            initializeCalls++
            return initializeResult
        }

        override fun startSocksProxy(port: Int): Int {
            startCalls++
            return startResult
        }

        override fun stop(): Int {
            stopCalls++
            return 0
        }
    }

    private fun tempDir(): String =
        Files.createTempDirectory("tor-manager-readiness").toFile().absolutePath

    @Test
    fun `a proxy that started is ready even though no log line ever arrives`() = runTest {
        val arti = FakeArti()
        val manager = TorManager(tempDir(), arti)

        manager.start()

        assertTrue(
            manager.isProxyReady(),
            "nativeStartSocksProxy returned 0, so 127.0.0.1:9050 is bound - the ProxySelector has " +
                    "to be given that address or desktop Tor proxies nothing"
        )
        assertEquals(Pair("127.0.0.1", 9050), manager.getSocksProxyAddress())

        val status = manager.statusFlow.value
        assertEquals(TorMode.ON, status.mode)
        assertEquals(TorState.RUNNING, status.state)
        assertTrue(status.running)
        assertNull(status.errorMessage)

        // The whole point: nothing above came from a log line.
        assertEquals(1, arti.initializeCalls)
        assertEquals(1, arti.startCalls)
        assertTrue(arti.registeredCallback != null, "the callback is still registered for platforms that use it")
    }

    @Test
    fun `readiness does not wait for a bootstrap percentage`() = runTest {
        // Cosmetic progress only: the manager may never learn a percentage on this platform, and a
        // listening SOCKS port already fails closed - traffic is carried by Tor or refused.
        val manager = TorManager(tempDir(), FakeArti())

        manager.start()

        assertTrue(manager.isProxyReady())
    }

    @Test
    fun `a proxy that failed to bind is never reported ready`() = runTest {
        // -3 from nativeStartSocksProxy: the wrapper could not bind 127.0.0.1:9050.
        val arti = FakeArti(startResult = -3)
        val manager = TorManager(tempDir(), arti)

        manager.start()

        assertFalse(manager.isProxyReady())
        assertNull(manager.getSocksProxyAddress())
        val status = manager.statusFlow.value
        assertEquals(TorState.ERROR, status.state)
        assertFalse(status.running)
        assertEquals("Start failed: -3", status.errorMessage)
    }

    @Test
    fun `a client that failed to initialize never reaches the proxy call`() = runTest {
        // -3 from nativeInitialize: TorClient::create_bootstrapped failed.
        val arti = FakeArti(initializeResult = -3)
        val manager = TorManager(tempDir(), arti)

        manager.start()

        assertEquals(0, arti.startCalls, "no point binding a proxy with no Arti client behind it")
        assertFalse(manager.isProxyReady())
        assertEquals(TorState.ERROR, manager.statusFlow.value.state)
        assertEquals("Init failed: -3", manager.statusFlow.value.errorMessage)
    }

    @Test
    fun `stopping closes the proxy for callers`() = runTest {
        val arti = FakeArti()
        val manager = TorManager(tempDir(), arti)
        manager.start()

        manager.stop()

        assertEquals(1, arti.stopCalls)
        assertFalse(manager.isProxyReady())
        assertNull(manager.getSocksProxyAddress())
        assertEquals(TorState.OFF, manager.statusFlow.value.state)
        assertEquals(TorMode.OFF, manager.statusFlow.value.mode)
    }

    @Test
    fun `the client is only initialized once across restarts`() = runTest {
        val arti = FakeArti()
        val manager = TorManager(tempDir(), arti)

        manager.start()
        manager.stop()
        manager.start()

        assertEquals(1, arti.initializeCalls)
        assertEquals(2, arti.startCalls)
        assertTrue(manager.isProxyReady())
    }

    @Test
    fun `log lines still refine the status where the library delivers them`() = runTest {
        // Android and the linuxArm64 build do call back; that path must keep working.
        val arti = FakeArti()
        val manager = TorManager(tempDir(), arti)
        manager.start()

        arti.registeredCallback!!.onLogLine("We have found that guard [scrubbed] is usable.")

        val status = manager.statusFlow.value
        assertEquals(100, status.bootstrapPercent)
        assertEquals("We have found that guard [scrubbed] is usable.", status.lastLogLine)
        assertTrue(manager.isProxyReady())
    }

    @Test
    fun `a progress line cannot demote a proxy that is already listening`() = runTest {
        val arti = FakeArti()
        val manager = TorManager(tempDir(), arti)
        manager.start()

        // Arrives from the spawned accept task, i.e. after the bind that already made the proxy
        // usable. Treating it as "still bootstrapping" would drop the ProxySelector back to direct.
        arti.registeredCallback!!.onLogLine("Sufficiently bootstrapped; system SOCKS now functional")

        assertEquals(TorState.RUNNING, manager.statusFlow.value.state)
        assertTrue(manager.isProxyReady())
    }

    @Test
    fun `a host without the native library still reports tor unavailable`() = runTest {
        val arti = FakeArti(isAvailable = false)
        val manager = TorManager(tempDir(), arti)

        manager.start()

        assertFalse(manager.isAvailable)
        assertFalse(manager.isProxyReady())
        assertEquals(0, arti.initializeCalls)
        assertEquals(TorState.ERROR, manager.statusFlow.value.state)
    }
}
