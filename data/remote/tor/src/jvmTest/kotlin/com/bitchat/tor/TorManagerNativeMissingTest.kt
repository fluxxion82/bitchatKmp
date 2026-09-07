package com.bitchat.tor

import com.bitchat.domain.tor.model.TorMode
import com.bitchat.domain.tor.model.TorState
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the state every Linux desktop host is in today: no `libarti_desktop.so` anywhere on
 * `java.library.path`. The build points the jvmTest JVM at an empty directory so the outcome does
 * not depend on whether the host happens to have built the macOS dylib.
 *
 * Before this was fixed, constructing [TorManager] threw UnsatisfiedLinkError - an Error, so the
 * `catch (e: Exception)` around the JNI call did not stop it - straight out of the Koin graph and
 * killed the desktop app before its window was ever shown.
 */
class TorManagerNativeMissingTest {

    private val dataDir: String = Files.createTempDirectory("tor-manager-test").toFile().absolutePath

    @BeforeTest
    fun `native library really is missing`() {
        assertFalse(
            TorManager.libraryLoaded,
            "Test precondition: libarti_desktop must not be loadable. java.library.path=" +
                    System.getProperty("java.library.path")
        )
    }

    @Test
    fun `constructing the manager does not throw when the native library is missing`() {
        val manager = TorManager(dataDir)

        val status = manager.statusFlow.value
        assertEquals(TorMode.OFF, status.mode)
        assertEquals(TorState.OFF, status.state)
        assertFalse(status.running)
        assertTrue(
            status.lastLogLine.startsWith("Tor unavailable: native library"),
            "expected an explanation in lastLogLine, got '${status.lastLogLine}'"
        )
    }

    /**
     * The whole point of the detailed reason: on a host with no Arti library the settings switch is
     * disabled, so [TorManager.start] - the only other place that ever sets `errorMessage` - can
     * never be reached from the UI. Seeded here, or `SettingsState.torErrorMessage` stays null all
     * the way to the settings screen and the user is shown the generic "tor not available in this
     * build", which names no file, no lookup path and no fix.
     */
    @Test
    fun `a fresh manager reports the actionable reason before anything is started`() {
        val status = TorManager(dataDir).statusFlow.value

        // Deliberately no start() call: the user cannot make one happen either.
        val message = status.errorMessage
        assertTrue(message != null, "expected errorMessage on a fresh manager with no Arti library")
        assertTrue(
            message.startsWith("Tor unavailable: native library") &&
                    message.contains(System.mapLibraryName("arti_desktop")) &&
                    message.contains("compose.application.resources.dir=") &&
                    message.contains("java.library.path=") &&
                    message.contains("build-desktop.sh"),
            "the reason has to name the library, both lookup paths and the fix, got: $message"
        )
        // Nothing has failed yet - this is a permanent property of the host, not a start error.
        assertEquals(TorState.OFF, status.state)
        assertEquals(TorMode.OFF, status.mode)
    }

    @Test
    fun `tor reports itself unavailable so the setting can be disabled`() {
        val manager = TorManager(dataDir)

        assertFalse(
            manager.isAvailable,
            "with no Arti library Tor can never run, so the settings switch must be disabled"
        )
    }

    @Test
    fun `proxy reports unavailable rather than throwing`() {
        val manager = TorManager(dataDir)

        assertFalse(manager.isProxyReady())
        assertNull(manager.getSocksProxyAddress())
    }

    @Test
    fun `start records an actionable error instead of crashing`() = runTest {
        val manager = TorManager(dataDir)

        manager.start()

        val status = manager.statusFlow.value
        assertEquals(TorState.ERROR, status.state)
        assertFalse(status.running)
        val message = status.errorMessage
        assertTrue(message != null, "expected an errorMessage")
        assertTrue(
            message.startsWith("Tor unavailable: native library") &&
                    message.contains(System.mapLibraryName("arti_desktop")) &&
                    message.contains("java.library.path="),
            "unexpected errorMessage: $message"
        )
        // Still degrades like "Tor is simply off" for everything downstream.
        assertFalse(manager.isProxyReady())
        assertNull(manager.getSocksProxyAddress())
    }

    @Test
    fun `start is idempotent and never retry-crashes`() = runTest {
        val manager = TorManager(dataDir)

        repeat(3) { manager.start() }

        assertEquals(TorState.ERROR, manager.statusFlow.value.state)
        assertFalse(manager.isProxyReady())
    }

    @Test
    fun `stop leaves tor off without touching the native library`() = runTest {
        val manager = TorManager(dataDir)
        manager.start()

        manager.stop()

        val status = manager.statusFlow.value
        assertEquals(TorMode.OFF, status.mode)
        assertEquals(TorState.OFF, status.state)
        assertFalse(status.running)
        // A stop cannot conjure up the missing library, so the reason survives it - both in the
        // error message the settings screen prints under the switch and in the log line.
        assertTrue(
            status.errorMessage?.startsWith("Tor unavailable: native library") == true,
            "expected the reason to survive stop(), got '${status.errorMessage}'"
        )
        assertTrue(status.lastLogLine.startsWith("Tor unavailable: native library"))
    }

    @Test
    fun `destroy is safe when the native library is missing`() {
        TorManager(dataDir).destroy()
    }
}
