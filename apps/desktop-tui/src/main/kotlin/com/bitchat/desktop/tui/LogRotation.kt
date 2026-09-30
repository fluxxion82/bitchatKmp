package com.bitchat.desktop.tui

import java.nio.file.Files
import java.nio.file.Path

/**
 * The rotation policy for the log the standard streams write to, separate from the descriptor plumbing so it can be
 * driven with fakes. [tick] runs on a timer.
 *
 * The streams keep writing to whatever file they are attached to. Rotation renames the log and attaches a new one; when
 * that attach fails the streams are left on the renamed file ("stranded"), which would otherwise grow without limit
 * while later ticks see no log file to size and skip rotation for good. So after a failed attach every tick retries
 * it, and if the stranded file grows by another [limit] bytes the output is discarded until the log can be attached.
 */
internal class LogRotation(
    private val file: Path,
    private val limit: Long,
    /** Flush the standard streams so everything written so far lands in the file they are attached to. */
    private val flush: () -> Unit,
    /** Open [file] and point the standard streams at it; throws when it cannot. */
    private val attach: (Path) -> Unit,
    /** Point the standard streams at /dev/null; throws when it cannot. */
    private val discard: () -> Unit,
    /** The message the UI shows, or null to clear it. */
    private val notice: (String?) -> Unit,
    private val report: (String) -> Unit,
) {
    /** The renamed file the streams still write to after a failed attach, and its size when that happened. */
    private var stranded: Path? = null
    private var strandedSize = 0L
    private var discarding = false

    fun tick() {
        if (stranded != null) {
            retry(stranded!!)
            return
        }
        if (!Files.exists(file)) {
            // Deleted from under us while running: the streams write to an unlinked file. Start a fresh one.
            attachOrReport()
            return
        }
        if (Files.size(file) < limit) return
        flush()
        val renamed = JvmTuiLog.rotateFiles(file)
        try {
            attach(file)
        } catch (error: Throwable) {
            stranded = renamed
            strandedSize = sizeOf(renamed)
            discarding = false
            val reason = error.message.orEmpty()
            notice("Log rotation failed ($reason)")
            report("bitchat-tui: log rotation failed: $reason")
        }
    }

    private fun retry(renamed: Path) {
        try {
            attach(file)
        } catch (error: Throwable) {
            if (!discarding && sizeOf(renamed) - strandedSize >= limit) {
                val reason = error.message.orEmpty()
                try {
                    discard()
                    discarding = true
                    notice("Log unavailable ($reason); output is discarded")
                    report("bitchat-tui: log unavailable, output is discarded: $reason")
                } catch (discardError: Throwable) {
                    report("bitchat-tui: cannot discard output either: ${discardError.message.orEmpty()}")
                }
            }
            return
        }
        stranded = null
        discarding = false
        notice(null)
    }

    private fun attachOrReport() {
        try {
            attach(file)
        } catch (error: Throwable) {
            report("bitchat-tui: cannot reopen the log: ${error.message.orEmpty()}")
        }
    }

    private fun sizeOf(path: Path): Long = try { Files.size(path) } catch (_: Exception) { 0L }
}
