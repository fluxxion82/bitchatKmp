package com.bitchat.desktop

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * The Tor default, end to end through the real startup graph: the real `commonLocal` + JVM `localModule` Tor
 * preference wiring (`TorPreferences`, the eagerly created `RequestedTorIntent`), the real `commonRepoModule`
 * `TorCapability` binding and the real `TorManager` from `torModule`, with and without the Arti library.
 *
 * Not covered: the platform `Settings.Factory` (java.util.prefs) and scratch Tor data dir are replaced, as the keychain
 * is not needed by this chain (user and identity preferences are never requested); `TorRepo`, the HTTP engine and the
 * network are not started. `RequestedTorIntentTest` (data:local:platform) and `PlatformTorCapabilityTest` (data:repo)
 * cover the pieces with fakes; this checks that the real pieces are wired together the way those tests assume.
 *
 * Each case runs in a child JVM (30 s bound), because `TorManager` decides once per JVM whether the library loaded.
 * "Library present" points `compose.application.resources.dir` at `data/remote/tor/native/libs/desktop`; without the
 * dylib there the present cases are skipped, loudly.
 */
class TorStartupGraphTest {
    private val libraryDirectory: File = File("../../data/remote/tor/native/libs/desktop").canonicalFile
    private val libraryFile = File(libraryDirectory, System.mapLibraryName("arti_desktop"))

    private class Outcome(val lines: Map<String, String>, val output: String) {
        operator fun get(key: String): String = lines[key] ?: error("no '$key' in the probe output:\n$output")
    }

    @Test
    fun absentKeyWithTheLibraryPresentIsOn() {
        val outcome = runProbe(stored = "absent", libraryPresent = true)
        assertIntent("ON", outcome)
        assertEquals("true", outcome["capability"])
        assertEquals("null", outcome["key.stored"], "reading the default must not write it")
    }

    @Test
    fun absentKeyWithTheLibraryMissingIsOff() {
        val outcome = runProbe(stored = "absent", libraryPresent = false)
        assertIntent("OFF", outcome)
        assertEquals("false", outcome["capability"])
        assertEquals("null", outcome["key.stored"], "reading the default must not write it")
    }

    @Test
    fun storedOffWithTheLibraryPresentStaysOff() {
        assertIntent("OFF", runProbe(stored = "OFF", libraryPresent = true))
    }

    @Test
    fun storedOnWithTheLibraryMissingStaysOn() {
        val outcome = runProbe(stored = "ON", libraryPresent = false)
        assertIntent("ON", outcome)
        assertEquals("false", outcome["capability"], "the intent survives; only the capability is absent")
    }

    @Test
    fun storedOnWithTheLibraryPresentStaysOn() {
        assertIntent("ON", runProbe(stored = "ON", libraryPresent = true))
    }

    @Test
    fun storedOffWithTheLibraryMissingStaysOff() {
        assertIntent("OFF", runProbe(stored = "OFF", libraryPresent = false))
    }

    private fun assertIntent(expected: String, outcome: Outcome) {
        assertEquals(expected, outcome["intent.current"], "RequestedTorIntent.current\n${outcome.output}")
        assertEquals(expected, outcome["intent.updates"], "RequestedTorIntent.updates.value\n${outcome.output}")
    }

    private fun runProbe(stored: String, libraryPresent: Boolean): Outcome {
        if (libraryPresent) {
            val message = "SKIPPED: the Arti library is not built at $libraryFile (run data/remote/tor/native/build-desktop.sh); " +
                "the 'library present' cases are not exercised on this machine"
            if (!libraryFile.isFile) System.err.println(message)
            assumeTrue(libraryFile.isFile, message)
        }
        val scratch = Files.createTempDirectory("bitchat-tor-startup")
        val emptyDirectory = Files.createDirectory(scratch.resolve("no-native-libs")).toFile()
        try {
            val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
            val command = buildList {
                add(java)
                if (libraryPresent) {
                    add("-Dcompose.application.resources.dir=${libraryDirectory.absolutePath}")
                } else {
                    // Replaced, not appended to: no inherited path may hold a libarti_desktop.
                    add("-Djava.library.path=${emptyDirectory.absolutePath}")
                }
                add("-cp"); add(System.getProperty("java.class.path"))
                add("com.bitchat.desktop.TorStartupProbeMain")
                add(stored); add(scratch.resolve("tor").toString())
            }
            val outputFile = scratch.resolve("probe-output.txt")
            val process = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(outputFile.toFile()).start()
            try {
                if (!process.waitFor(30, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    throw AssertionError("probe timed out after 30 s:\n" + Files.readString(outputFile))
                }
                val output = Files.readString(outputFile)
                assertEquals(0, process.exitValue(), "probe failed:\n$output")
                val lines = output.lines().filter { it.startsWith("PROBE ") }
                    .associate { line -> line.removePrefix("PROBE ").substringBefore('=') to line.substringAfter('=') }
                val outcome = Outcome(lines, output)
                // The precondition the whole case rests on: the library really was (not) there.
                assertEquals(libraryPresent.toString(), outcome["tor.available"], "TorManager.isAvailable\n$output")
                return outcome
            } finally {
                if (process.isAlive) process.destroyForcibly()
            }
        } finally {
            scratch.toFile().deleteRecursively()
        }
    }
}
