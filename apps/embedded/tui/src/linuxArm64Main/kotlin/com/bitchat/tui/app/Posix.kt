@file:OptIn(ExperimentalForeignApi::class)

package com.bitchat.tui.app

import com.bitchat.local.statedir.StateDirectory
import com.bitchat.local.statedir.StateDirectoryException
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.setUnhandledExceptionHook
import kotlin.system.exitProcess
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.posix.ENOENT
import platform.posix.F_DUPFD_CLOEXEC
import platform.posix.O_APPEND
import platform.posix.O_CLOEXEC
import platform.posix.O_CREAT
import platform.posix.O_WRONLY
import platform.posix.STDERR_FILENO
import platform.posix.STDOUT_FILENO
import platform.posix.chmod
import platform.posix.close
import platform.posix.dup2
import platform.posix.errno
import platform.posix.fchmod
import platform.posix.fcntl
import platform.posix.fstat
import platform.posix.ftruncate
import platform.posix.isatty
import platform.posix.open
import platform.posix.rename
import platform.posix.stat
import platform.posix.strerror
import platform.posix.write

/**
 * Where stdout and stderr go while the terminal UI runs. Mosaic draws on the controlling terminal,
 * so anything printed to a terminal stdout would land between its frames: when stdout is a terminal
 * (a manual run on the console or over SSH) both go to `~/.bitchat/tui.log`, rotated at [MAX_BYTES]
 * with [KEEP] old files, or to `/dev/null` when that log cannot be set up or rotated (with a
 * [notice] for the UI). If even that fails, startup stops rather than run with output on the
 * terminal. Under systemd (stdout is the journal) or a pipe they are left alone.
 */
internal object TuiLog {
    const val MAX_BYTES = 5L * 1024 * 1024
    const val KEEP = 2

    /** The log file stdout and stderr point at: always `~/.bitchat/tui.log`, or null when not logging to a file. */
    private var logFile: String? = null

    /** A copy of the terminal, for the one line a fatal error leaves there; -1 when there is none. */
    private var terminal = -1

    /** Which Java standard streams were terminals before redirection. */
    private var redirectStdout = false
    private var redirectStderr = false

    /** A redirected descriptor used to measure and truncate the active log. */
    private var logDescriptor = -1

    private val _notice = MutableStateFlow<String?>(null)

    /** Why output is being discarded, for the UI to show; null while all is well. */
    val notice: StateFlow<String?> = _notice.asStateFlow()

    /**
     * Redirects stdout and stderr when stdout is a terminal. When neither the log nor /dev/null can
     * take them, prints one line on the terminal and exits with status 1 (Mosaic has not started,
     * so the terminal is still as the shell left it).
     */
    fun redirectIfTerminal() {
        redirectStdout = isatty(STDOUT_FILENO) == 1
        redirectStderr = isatty(STDERR_FILENO) == 1
        if (!redirectStdout && !redirectStderr) return
        terminal = fcntl(STDERR_FILENO, F_DUPFD_CLOEXEC, 3)
        reportFatalErrorsOnTheTerminal()
        val failure = openLog() ?: return
        val discarded = pointStdioAt("/dev/null", create = false)
        if (discarded != null) {
            tellTerminal("bitchat-tui: cannot keep output off the terminal: $failure; $discarded")
            exitProcess(1)
        }
        _notice.value = "Log unavailable ($failure); output is discarded"
    }

    /**
     * Rotates the log once it has reached [MAX_BYTES]: `tui.log.1` becomes `tui.log.2` (the oldest
     * is dropped), the log becomes `tui.log.1`, and a new `tui.log` is opened onto stdout and
     * stderr. If that fails, output is discarded from then on (never the terminal: stdout and
     * stderr stay on a file or /dev/null) and [notice] says so. If even /dev/null cannot be opened
     * (no descriptors left), the file output is on is truncated instead and rotation is tried
     * again at the next check, so the log stays bounded either way. Call periodically; a no-op
     * when not logging to a file.
     */
    fun rotateIfLarge() {
        val file = logFile ?: return
        val size = memScoped {
            val info = alloc<stat>()
            if (logDescriptor >= 0 && fstat(logDescriptor, info.ptr) == 0) info.st_size else return
        }
        if (size < MAX_BYTES) return
        val failure = rotate(file) ?: return
        if (pointStdioAt("/dev/null", create = false) == null) {
            logFile = null
            _notice.value = "Log rotation failed ($failure); output is discarded"
            return
        }
        // Stdout and stderr still share one file (the old log, whatever it is called now): keep
        // it bounded by emptying it, and keep the path so the next check tries rotating again.
        ftruncate(logDescriptor, 0)
        _notice.value = "Log rotation failed ($failure); the log is emptied instead"
    }

    /** Sets up `~/.bitchat/tui.log` on stdout and stderr; returns why not, or null when done. */
    private fun openLog(): String? {
        // The log goes where the rest of the state goes, under the same rule: this user's own directory.
        val dir = try {
            StateDirectory.own()
        } catch (e: StateDirectoryException) {
            return e.message
        }
        val file = "$dir/tui.log"
        chmod(file, 0x180u) // 0600 before anything else, even if it cannot be reopened; missing is fine.
        val failure = if (fileSize(file)?.let { it >= MAX_BYTES } == true) rotate(file) else pointStdioAt(file, create = true)
        if (failure == null) logFile = file
        return failure
    }

    /** Shifts the old files and opens a new [file] onto stdout and stderr; returns why not, or null when done. */
    private fun rotate(file: String): String? {
        for (index in KEEP - 1 downTo 1) {
            if (rename("$file.$index", "$file.${index + 1}") != 0 && errno != ENOENT) {
                return "cannot rename $file.$index: ${lastError()}"
            }
        }
        if (rename(file, "$file.1") != 0 && errno != ENOENT) return "cannot rename $file: ${lastError()}"
        for (index in 1..KEEP) chmod("$file.$index", 0x180u) // 0600; a missing file is fine.
        return pointStdioAt(file, create = true)
    }

    /**
     * Opens [file] (appending, mode 0600 even when it already existed) onto stdout and stderr and
     * checks that both now refer to it. Returns why not, or null when done.
     */
    private fun pointStdioAt(file: String, create: Boolean): String? {
        val flags = O_WRONLY or O_APPEND or O_CLOEXEC or (if (create) O_CREAT else 0)
        val fd = open(file, flags, 0x180) // 0600
        if (fd < 0) return "cannot open $file: ${lastError()}"
        try {
            if (create && fchmod(fd, 0x180u) != 0) return "cannot restrict $file to its owner: ${lastError()}"
            if (redirectStdout && dup2(fd, STDOUT_FILENO) < 0) return "cannot redirect stdout: ${lastError()}"
            if (redirectStderr && dup2(fd, STDERR_FILENO) < 0) return "cannot redirect stderr: ${lastError()}"
            val target = identity(fd)
            if (target == null ||
                (redirectStdout && identity(STDOUT_FILENO) != target) ||
                (redirectStderr && identity(STDERR_FILENO) != target)
            ) {
                return "stdout or stderr does not point at $file"
            }
            logDescriptor = if (redirectStdout) STDOUT_FILENO else STDERR_FILENO
            return null
        } finally {
            if (fd > STDERR_FILENO) close(fd)
        }
    }

    /**
     * Once stdout and stderr no longer reach the terminal, an exception that kills the app (say, a
     * home directory it cannot write its settings to) would leave no trace there, or none at all
     * when output is discarded. So one plain line naming the error, and where the details are,
     * goes to the copy of the terminal; the trace itself still goes to stdout (the log).
     */
    @OptIn(ExperimentalNativeApi::class)
    private fun reportFatalErrorsOnTheTerminal() {
        if (terminal < 0) return
        setUnhandledExceptionHook { error ->
            error.printStackTrace()
            val where = logFile?.let { "details in $it" } ?: "no log"
            tellTerminal("bitchat-tui: ${error::class.simpleName}: ${error.message.orEmpty()} ($where)")
        }
    }

    /** Writes [line], reduced to printable ASCII, on its own line of the saved terminal. */
    private fun tellTerminal(line: String) {
        val fd = if (terminal >= 0) terminal else STDERR_FILENO
        val text = "\r\n" + line.map { if (it.code in 0x20..0x7E) it else '?' }.joinToString("").take(400) + "\r\n"
        val bytes = text.encodeToByteArray()
        bytes.usePinned { write(fd, it.addressOf(0), bytes.size.convert()) }
    }

    /** The device and inode [fd] refers to. */
    private fun identity(fd: Int): Pair<ULong, ULong>? = memScoped {
        val info = alloc<stat>()
        if (fstat(fd, info.ptr) == 0) info.st_dev to info.st_ino else null
    }

    private fun lastError(): String = strerror(errno)?.toKString() ?: "error $errno"
}
