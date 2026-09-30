package com.bitchat.desktop

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SingleInstanceLockTest {
    @Test
    fun secondLockInTheSameJvmFails() {
        val directory = Files.createTempDirectory("bitchat-lock-test")
        val first = assertNotNull(SingleInstanceLock.tryAcquire(directory))

        assertNull(SingleInstanceLock.tryAcquire(directory))

        first.close()
    }

    @Test
    fun failureReportsToStderrAndUsesTheInjectedExit() {
        val directory = Files.createTempDirectory("bitchat-lock-test")
        val first = assertNotNull(SingleInstanceLock.tryAcquire(directory))
        val stderr = ByteArrayOutputStream()
        var exitCode: Int? = null

        SingleInstanceLock.acquireOrExit(directory, PrintStream(stderr), exit = { exitCode = it })

        assertEquals(1, exitCode)
        assertEquals("another bitchat desktop app is running\n", stderr.toString())
        first.close()
    }

    @Test
    fun anotherProcessCannotLockUntilTheParentReleasesIt() {
        val directory = Files.createTempDirectory("bitchat-lock-test")
        val parent = assertNotNull(SingleInstanceLock.tryAcquire(directory))

        assertEquals(1, runProbe(directory))

        parent.close()

        assertEquals(0, runProbe(directory))
    }

    private fun runProbe(directory: Path): Int {
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val process = ProcessBuilder(
            java,
            "-cp",
            System.getProperty("java.class.path"),
            "com.bitchat.desktop.LockProbeMain",
            directory.toString(),
        ).start()
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "lock probe timed out")
            return process.exitValue()
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }
}
