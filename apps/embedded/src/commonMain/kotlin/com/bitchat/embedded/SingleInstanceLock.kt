@file:OptIn(ExperimentalForeignApi::class)

package com.bitchat.embedded

import kotlin.system.exitProcess
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.EEXIST
import platform.posix.EWOULDBLOCK
import platform.posix.O_CLOEXEC
import platform.posix.O_CREAT
import platform.posix.O_DIRECTORY
import platform.posix.O_NOFOLLOW
import platform.posix.O_RDONLY
import platform.posix.O_RDWR
import platform.posix.STDERR_FILENO
import platform.posix.STDIN_FILENO
import platform.posix.STDOUT_FILENO
import platform.posix.close
import platform.posix.errno
import platform.posix.fstat
import platform.posix.ftruncate
import platform.posix.getenv
import platform.posix.geteuid
import platform.posix.getpid
import platform.posix.mkdir
import platform.posix.open
import platform.posix.pread
import platform.posix.pwrite
import platform.posix.stat
import platform.posix.strerror
import platform.posix.ttyname
import platform.posix.unlinkat
import platform.posix.write

/**
 * One bitchat embedded app per board. `bitchat-tui` and `bitchat-embedded` drive the same LoRa
 * radio and the same Bluetooth adapter, with the same identity, out of the same `~/.bitchat`; two
 * of them at once split what is received between them (seen on 2026-10-05: the unit on tty1 and a
 * copy started by hand over SSH). So both binaries take this one lock before anything else.
 *
 * The lock is `flock` on `~/.bitchat/instance.lock`, through a descriptor that is never closed.
 * It lives in the kernel, not in the file: it is gone when its holder exits, crashes or loses
 * power, and a lock file left behind blocks nothing. (A pid file would go stale, and the terminal
 * UI's unit restarts after every exit.) What the file contains only names the holder in the
 * refusal. The lock is per home directory: an app run as another user is not seen. And it is
 * tied to the file's name: deleting the file while an app runs lets a second one start.
 */
object SingleInstanceLock {
    /**
     * Exit status of an app that was refused: `EX_TEMPFAIL` of sysexits.h, which nothing else in
     * either app exits with. Both units name it in `RestartPreventExitStatus=`, so systemd does
     * not start the app again every few seconds for as long as another one holds the board.
     */
    const val REFUSED_EXIT_STATUS = 75

    /**
     * Takes the lock for the app called [name], or, when another app holds it, prints one line on
     * stderr and exits with [REFUSED_EXIT_STATUS]. Call it first, right after `--version` is
     * handled: nothing here touches a terminal's modes, the display, the radio or Bluetooth.
     *
     * A lock that cannot be set up or trusted (no writable `~/.bitchat`; a directory or lock file
     * that another user could have put there or could open) is not a second app. That prints a
     * warning and the app starts without the lock: refusing would keep the unit down until
     * someone repaired it by hand.
     */
    fun acquireOrExit(
        name: String,
        directory: String = defaultDirectory(),
        stderr: (String) -> Unit = ::printToStandardError,
        exit: (Int) -> Unit = ::exitProcess,
    ) {
        when (val attempt = tryAcquire(directory, Holder(name, getpid(), terminalName()))) {
            is Attempt.Acquired -> Unit // The descriptor stays open until the process ends.
            is Attempt.Held -> {
                stderr(refusalLine(attempt.holder))
                exit(REFUSED_EXIT_STATUS)
            }
            is Attempt.Unavailable -> stderr("$name: no instance lock (${attempt.reason}); starting without one")
        }
    }

    /** One attempt at the lock in [directory] (created 0700 when missing), recording [self] as its holder. */
    internal fun tryAcquire(directory: String, self: Holder): Attempt {
        if (mkdir(directory, MODE_0700.convert()) != 0 && errno != EEXIST) {
            return Attempt.Unavailable("cannot create $directory: ${lastError()}")
        }
        // The directory is opened and checked before anything in it is trusted: whoever can write
        // a directory can move any file to the lock file's name, and the holder's line is written
        // into that file. A private ~/.bitchat always passes; with HOME unset the directory is
        // under /tmp, where another user may have made it first.
        val folder = open(directory, O_RDONLY or O_DIRECTORY or O_CLOEXEC)
        if (folder < 0) return Attempt.Unavailable("cannot open $directory: ${lastError()}")
        try {
            if (!isPrivateDirectory(folder)) {
                return Attempt.Unavailable("$directory is not a directory only this user can write")
            }
            return lock("$directory/$LOCK_FILE", folder, self)
        } finally {
            close(folder)
        }
    }

    /** Opens the lock file inside the checked directory [folder] and tries to lock it; [file] is its path, for messages. */
    private fun lock(file: String, folder: Int, self: Holder): Attempt {
        // O_CLOEXEC: a command the app runs (the LoRa daemon backend uses popen) must not inherit
        // the descriptor, or it would go on holding the board after the app itself is gone.
        // O_NOFOLLOW and the check after it: the name has to be this user's own plain file, not a
        // link to another one.
        val descriptor = openInside(folder, file, LOCK_FILE, O_RDWR or O_CREAT or O_NOFOLLOW or O_CLOEXEC, MODE_0600)
        if (descriptor < 0) return Attempt.Unavailable("cannot open $file: ${lastError()}")
        val mode = ownPlainFileMode(descriptor)
        if (mode == null) {
            close(descriptor)
            return Attempt.Unavailable("$file is not a plain file of this user alone")
        }
        if ((mode and OPEN_TO_OTHERS) != 0) {
            // Whoever can open the file can hold its lock with no app running, and keeps that hold
            // through a chmod. (The apps create it 0600; a copy restored without its modes is not.)
            // So the name is given up instead, and the next start makes a file of its own.
            val removed = unlinkat(folder, LOCK_FILE, 0) == 0
            val outcome = if (removed) "has been removed" else "cannot be removed: ${lastError()}"
            close(descriptor)
            return Attempt.Unavailable("$file was open to other users and $outcome")
        }
        if (lockWithoutWaiting(descriptor) != 0) {
            val attempt = if (errno == EWOULDBLOCK) {
                Attempt.Held(readHolder(descriptor))
            } else {
                Attempt.Unavailable("cannot lock $file: ${lastError()}")
            }
            close(descriptor)
            return attempt
        }
        writeHolder(descriptor, self)
        return Attempt.Acquired(descriptor)
    }

    /** Whether [descriptor] is a directory that this user owns and that neither its group nor others can write. */
    private fun isPrivateDirectory(descriptor: Int): Boolean = memScoped {
        val info = alloc<stat>()
        fstat(descriptor, info.ptr) == 0 &&
            info.st_uid == geteuid() &&
            (info.st_mode.convert<Int>() and WRITABLE_BY_OTHERS) == 0
    }

    /**
     * The permission bits of [descriptor] when it is a regular file that this user owns and that
     * has no second name (hard link); null when it is anything else.
     */
    private fun ownPlainFileMode(descriptor: Int): Int? = memScoped {
        val info = alloc<stat>()
        if (fstat(descriptor, info.ptr) != 0) return null
        val mode = info.st_mode.convert<Int>()
        val ownPlainFile = (mode and FILE_TYPE_MASK) == REGULAR_FILE &&
            info.st_uid == geteuid() &&
            info.st_nlink.convert<Int>() == 1
        if (ownPlainFile) mode and PERMISSIONS else null
    }

    private fun writeHolder(descriptor: Int, holder: Holder) {
        // Emptied first: if the write fails, a refused app reads nothing rather than the last holder.
        ftruncate(descriptor, 0)
        val bytes = holder.encode().encodeToByteArray()
        bytes.usePinned { pwrite(descriptor, it.addressOf(0), bytes.size.convert(), 0) }
    }

    private fun readHolder(descriptor: Int): Holder? {
        val bytes = ByteArray(HOLDER_BYTES)
        val count = bytes.usePinned { pread(descriptor, it.addressOf(0), bytes.size.convert(), 0) }
        return if (count > 0) Holder.parse(bytes.decodeToString(0, count.toInt())) else null
    }

    /** The terminal this process was started on, when there is one: tty1 under the terminal UI's unit, a pts over SSH. */
    private fun terminalName(): String? =
        listOf(STDIN_FILENO, STDERR_FILENO, STDOUT_FILENO).firstNotNullOfOrNull { ttyname(it)?.toKString() }

    /** `~/.bitchat` as the data layer resolves it (`LinuxSettingsFactory`): the state this lock guards. */
    private fun defaultDirectory(): String = (getenv("HOME")?.toKString() ?: "/tmp") + "/.bitchat"

    private fun printToStandardError(line: String) {
        val bytes = "$line\n".encodeToByteArray()
        bytes.usePinned { write(STDERR_FILENO, it.addressOf(0), bytes.size.convert()) }
    }

    private fun lastError(): String = strerror(errno)?.toKString() ?: "error $errno"

    private const val LOCK_FILE = "instance.lock"
    private const val HOLDER_BYTES = 256
    private const val MODE_0700 = 0x1C0
    private const val MODE_0600 = 0x180
    private const val FILE_TYPE_MASK = 0xF000 // S_IFMT
    private const val REGULAR_FILE = 0x8000 // S_IFREG
    private const val PERMISSIONS = 0x1FF // 0777
    private const val WRITABLE_BY_OTHERS = 0x12 // 0022
    private const val OPEN_TO_OTHERS = 0x3F // 0077
}

/*
 * The two calls that differ between the boards and the host the tests run on, because of where
 * Kotlin/Native keeps them: `flock` is in `platform.linux` for Linux and in `platform.posix` for
 * macOS, and `openat` is left out of the macOS bindings altogether.
 */

/** `flock(descriptor, LOCK_EX or LOCK_NB)`: 0 when the lock was taken, -1 with `errno` set when not. */
internal expect fun lockWithoutWaiting(descriptor: Int): Int

/**
 * `openat(folder, name, flags, mode)`: opens [name] in the directory that [folder] is, whatever
 * its path leads to by now. [path] is that file's path, which only the macOS host uses.
 */
internal expect fun openInside(folder: Int, path: String, name: String, flags: Int, mode: Int): Int

/** What one try at the lock found. */
internal sealed interface Attempt {
    /** This process holds the lock for as long as [descriptor] stays open. */
    class Acquired(val descriptor: Int) : Attempt

    /** Another process holds it; [holder] is what that process wrote about itself, when it can be read. */
    class Held(val holder: Holder?) : Attempt

    /** The lock could not be set up, which says nothing about another process. */
    class Unavailable(val reason: String) : Attempt
}

/** Who holds the lock, as the holder writes it into the lock file. Used for the refusal line and nothing else. */
internal data class Holder(val name: String?, val pid: Int?, val tty: String?) {
    fun encode(): String = buildString {
        name?.let { append("name=").append(it).append('\n') }
        pid?.let { append("pid=").append(it).append('\n') }
        tty?.let { append("tty=").append(it).append('\n') }
    }

    companion object {
        /**
         * Reads [encode]'s lines back, keeping a field only in the shape an app writes it: the
         * result is printed on a terminal, and the file holds whatever was last left in it.
         */
        fun parse(text: String): Holder? {
            val fields = text.lineSequence().mapNotNull { line ->
                val separator = line.indexOf('=')
                if (separator > 0) line.substring(0, separator) to line.substring(separator + 1) else null
            }.toMap()
            val name = fields["name"]?.takeIf { it.length in 1..32 && it.all { c -> c in 'a'..'z' || c in '0'..'9' || c == '-' } }
            val pid = fields["pid"]?.takeIf { it.length in 1..9 && it.all { c -> c in '0'..'9' } }?.toInt()?.takeIf { it > 0 }
            val tty = fields["tty"]?.takeIf {
                it.length in 6..64 && it.startsWith("/dev/") &&
                    it.all { c -> c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '/' || c == '_' || c == '-' }
            }
            return if (name == null && pid == null && tty == null) null else Holder(name, pid, tty)
        }
    }
}

/** The one line a refused app prints; names the [holder] as far as it is known. */
internal fun refusalLine(holder: Holder?): String {
    val process = listOfNotNull(holder?.pid?.let { "pid $it" }, holder?.tty?.let { "on $it" }).joinToString(" ")
    val who = listOfNotNull(holder?.name, process.ifEmpty { null }).joinToString(", ")
    // The terminal UI can be shared: its unit runs it in a tmux session an SSH login attaches to.
    val attach = if (holder?.name == "bitchat-tui") "; attach with ~/bitchat-tui-attach" else ""
    return "another bitchat embedded app is running" + (if (who.isEmpty()) "" else " ($who)") + attach
}
