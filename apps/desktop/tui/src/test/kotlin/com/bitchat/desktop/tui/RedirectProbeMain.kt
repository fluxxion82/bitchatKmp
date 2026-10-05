package com.bitchat.desktop.tui

import com.sun.jna.Library
import com.sun.jna.Native
import java.io.File
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * The child JVM of [PtyRedirectTest]: redirects like the app does, writes through Java and through libc, and
 * reports on what it did. Run under a pty so fd 1/2 are terminals.
 *
 * `RedirectProbeMain <mode> <log directory>`; modes: `streams` (writes once, then a saved-stderr report) and
 * `rotate` (forces several rotations, writing in between).
 */
object RedirectProbeMain {
    private interface LibC : Library {
        fun write(fd: Int, bytes: ByteArray, count: Long): Long
    }

    private val libc: LibC by lazy { Native.load("c", LibC::class.java) }

    private fun nativeWrite(fd: Int, text: String) {
        val bytes = (text + "\n").toByteArray()
        libc.write(fd, bytes, bytes.size.toLong())
    }

    /** One line through System.out, System.err and libc `write` on fd 1 and 2, all tagged with [tag]. */
    private fun emit(tag: String) {
        println("JAVA-OUT-$tag")
        System.err.println("JAVA-ERR-$tag")
        nativeWrite(1, "NATIVE-OUT-$tag")
        nativeWrite(2, "NATIVE-ERR-$tag")
    }

    private fun openDescriptors(): Int = File("/dev/fd").list()?.size ?: -1

    @JvmStatic
    fun main(args: Array<String>) {
        val mode = args[0]
        val directory = Path.of(args[1])
        JvmTuiLog.redirectIfTerminal(directory)
        when (mode) {
            "streams" -> {
                emit("STREAMS")
                JvmTuiLog.reportOriginal("SAVED-STDERR-REPORT")
            }

            "rotate" -> {
                emit("BEFORE-ROTATION")
                val filler = "x".repeat(99)
                var descriptorsAfterFirst = -1
                repeat(3) { round ->
                    // A little over the limit, so the next tick rotates.
                    repeat((JvmTuiLog.maxBytes / 100).toInt() + 100) { println(filler) }
                    JvmTuiLog.rotateIfLarge()
                    emit("ROUND-$round")
                    if (round == 0) descriptorsAfterFirst = openDescriptors()
                }
                val descriptorsAfterLast = openDescriptors()
                JvmTuiLog.reportOriginal(
                    if (descriptorsAfterFirst == descriptorsAfterLast) "FDS-STABLE"
                    else "FDS-LEAK $descriptorsAfterFirst -> $descriptorsAfterLast",
                )
                JvmTuiLog.reportOriginal("SAVED-STDERR-REPORT")
            }

            else -> error("unknown mode $mode")
        }
        exitProcess(0)
    }
}
