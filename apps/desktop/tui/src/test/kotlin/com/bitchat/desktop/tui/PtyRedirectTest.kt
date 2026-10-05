package com.bitchat.desktop.tui

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression tests for the fd-level redirection, which only means something when fd 1/2 are terminals. Each test runs
 * a child JVM ([RedirectProbeMain]) on a real pty, through `scripts/tui-smoke.py` (python3, stdlib only), which
 * provides the pty and fails if any forbidden text reaches it. The JVM test then reads the files the child wrote.
 * Each child is bounded by 30 s.
 */
class PtyRedirectTest {
    private lateinit var directory: Path
    private val log get() = directory.resolve("desktop-tui.log")
    private val allStreamTags = listOf("JAVA-OUT", "JAVA-ERR", "NATIVE-OUT", "NATIVE-ERR")

    @BeforeTest
    fun setUp() {
        directory = Files.createTempDirectory("bitchat-tui-pty")
    }

    @AfterTest
    fun tearDown() {
        Files.walk(directory).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
    }

    @Test
    fun withBothStreamsOnATtyJavaAndNativeWritesGoToTheLogAndNothingToThePty() {
        runOnPty(
            mode = "streams",
            expectOnPty = listOf("SAVED-STDERR-REPORT"), // the saved duplicate of the original stderr still reaches the terminal
            forbidOnPty = allStreamTags.map { "$it-STREAMS" },
        )

        val text = Files.readString(log)
        for (tag in allStreamTags) assertTrue("$tag-STREAMS" in text, "$tag-STREAMS is in the log: $text")
        assertFalse("SAVED-STDERR-REPORT" in text, "the fatal-report channel is not the log")
        assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(log)))
    }

    @Test
    fun withStdoutInAFileAndStderrOnATtyStdoutIsLeftAloneAndStderrGoesToTheLog() {
        val userFile = directory.resolve("user-stdout.txt")

        runOnPty(
            mode = "streams",
            shellRedirect = ">${quote(userFile)}",
            expectOnPty = listOf("SAVED-STDERR-REPORT"),
            forbidOnPty = allStreamTags.map { "$it-STREAMS" },
        )

        val user = Files.readString(userFile)
        assertTrue("JAVA-OUT-STREAMS" in user && "NATIVE-OUT-STREAMS" in user, "stdout text is in the user's file: $user")
        assertFalse("ERR" in user, "no stderr text in the user's file: $user")
        val text = Files.readString(log)
        assertTrue("JAVA-ERR-STREAMS" in text && "NATIVE-ERR-STREAMS" in text, "stderr text is in the log: $text")
        assertFalse("OUT-STREAMS" in text, "stdout text is not in the log: $text")
    }

    @Test
    fun withStderrInAFileAndStdoutOnATtyStderrIsLeftAloneAndStdoutGoesToTheLog() {
        val userFile = directory.resolve("user-stderr.txt")

        runOnPty(
            mode = "streams",
            shellRedirect = "2>${quote(userFile)}",
            expectOnPty = emptyList(),
            forbidOnPty = allStreamTags.map { "$it-STREAMS" } + "SAVED-STDERR-REPORT",
        )

        val user = Files.readString(userFile)
        assertTrue("JAVA-ERR-STREAMS" in user && "NATIVE-ERR-STREAMS" in user, "stderr text is in the user's file: $user")
        assertTrue("SAVED-STDERR-REPORT" in user, "the saved duplicate is the user's stderr file: $user")
        assertFalse("OUT-STREAMS" in user, "no stdout text in the user's file: $user")
        val text = Files.readString(log)
        assertTrue("JAVA-OUT-STREAMS" in text && "NATIVE-OUT-STREAMS" in text, "stdout text is in the log: $text")
        assertFalse("ERR-STREAMS" in text, "stderr text is not in the log: $text")
    }

    @Test
    fun afterForcedRotationsWritesLandInTheNewFileAndNoDescriptorLeaks() {
        runOnPty(
            mode = "rotate",
            expectOnPty = listOf("FDS-STABLE", "SAVED-STDERR-REPORT"),
            forbidOnPty = allStreamTags.map { "$it-" } + "FDS-LEAK" + "xxxxxxxxxx",
        )

        val current = Files.readString(log)
        for (tag in allStreamTags) assertTrue("$tag-ROUND-2" in current, "$tag-ROUND-2 is in the new log: ${current.take(300)}")
        assertFalse("ROUND-1" in current || "ROUND-0" in current || "BEFORE-ROTATION" in current, "the new log holds only what came after the last rotation")
        assertTrue(Files.size(log) < JvmTuiLog.maxBytes)
        assertTrue("JAVA-OUT-ROUND-1" in Files.readString(directory.resolve("desktop-tui.log.1")), "the previous log became .1")
        assertTrue("JAVA-OUT-ROUND-0" in Files.readString(directory.resolve("desktop-tui.log.2")), "the one before became .2")
        assertFalse(Files.exists(directory.resolve("desktop-tui.log.3")))
    }

    /** Runs the probe on a pty via the smoke script; it must exit 0 by itself, show [expectOnPty] and never show [forbidOnPty]. */
    private fun runOnPty(
        mode: String,
        expectOnPty: List<String>,
        forbidOnPty: List<String>,
        shellRedirect: String? = null,
    ) {
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val probe = listOf(
            java, "-Djava.awt.headless=true", "-cp", System.getProperty("java.class.path"),
            "com.bitchat.desktop.tui.RedirectProbeMain", mode, directory.toString(),
        )
        val command = if (shellRedirect == null) probe else listOf("sh", "-c", "exec " + probe.joinToString(" ") { quote(it) } + " " + shellRedirect)
        // Found by walking up to the settings file, not by counting "..": this module has moved once already.
        val repositoryRoot = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("settings.gradle.kts")) }
        val smoke = repositoryRoot.resolve("scripts").resolve("tui-smoke.py")
        check(Files.isRegularFile(smoke)) { "missing $smoke" }
        val arguments = buildList {
            add("python3"); add(smoke.toString()); add("--expect-exit"); add("0")
            expectOnPty.forEach { add("--expect"); add(it) }
            forbidOnPty.forEach { add("--forbid"); add(it) }
            add("--"); addAll(command)
        }
        val output = directory.resolve("smoke-output-${System.nanoTime()}.txt")
        val process = ProcessBuilder(arguments).redirectErrorStream(true).redirectOutput(output.toFile()).start()
        try {
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroy() // SIGTERM: the script kills the session it started on its way out
                if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
                throw AssertionError("pty run timed out after 30 s:\n" + Files.readString(output))
            }
            assertEquals(0, process.exitValue(), "pty run failed:\n" + Files.readString(output))
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private fun quote(path: Path) = quote(path.toString())
    private fun quote(text: String) = "'" + text.replace("'", "'\\''") + "'"
}
