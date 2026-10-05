package com.bitchat.desktop.tui

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Platform
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Keeps everything the process writes to stdout/stderr, Java and native alike, out of Mosaic's terminal frames.
 *
 * Mosaic binds /dev/tty itself, not fd 1/2, so fd 1 and fd 2 can be pointed at the log file: for EACH of them that
 * is a terminal, `dup2` the log file over it. System.out/err stay the JVM's own streams (they write to fd 1/2, now
 * the file) and so do native writers such as the Arti wrapper's stderr. A saved CLOEXEC duplicate of the original
 * stderr is kept for fatal reports.
 */
object JvmTuiLog {
    const val maxBytes = 5L * 1024 * 1024
    private const val LOG_NAME = "desktop-tui.log"
    private const val TTY_PATH = "/dev/tty"
    private var savedStderr = -1
    private var redirectOut = false
    private var redirectErr = false
    private var rotation: LogRotation? = null
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private interface LibC : Library {
        fun isatty(fd: Int): Int
        fun close(fd: Int): Int
        fun dup2(source: Int, target: Int): Int
        fun write(fd: Int, bytes: ByteArray, count: Long): Long

        // Variadic in C: on Apple arm64 the variable arguments are passed on the stack, so JNA must be told
        // they are variable by declaring them as varargs.
        fun open(path: String, flags: Int, vararg mode: Any?): Int
        fun fcntl(fd: Int, command: Int, vararg argument: Any?): Int
    }

    // Loaded on first use, not at object initialisation, so a host without libc never fails the whole app there.
    private val libc: LibC by lazy { Native.load(if (Platform.isWindows()) "msvcrt" else "c", LibC::class.java) }

    /** True when this process has a controlling terminal, which is what Mosaic needs to draw on /dev/tty. */
    fun hasControllingTerminal(): Boolean {
        val constants = Constants.forOs(System.getProperty("os.name"))
        val fd = libc.open(TTY_PATH, constants.rdwr or constants.noctty)
        if (fd < 0) return false
        libc.close(fd)
        return true
    }

    fun redirectIfTerminal(directory: Path = defaultDirectory()) {
        val constants = Constants.forOs(System.getProperty("os.name"))
        redirectOut = libc.isatty(1) == 1
        redirectErr = libc.isatty(2) == 1
        if (!redirectOut && !redirectErr) return
        savedStderr = libc.fcntl(2, constants.dupFdCloexec, 3)
        try {
            Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(directoryPermissions))
            restrict(directory, directoryPermissions)
            val file = directory.resolve(LOG_NAME)
            if (Files.exists(file) && shouldRotate(Files.size(file))) rotateFiles(file)
            pointStandardStreamsAt(file, constants)
            rotation = LogRotation(
                file = file,
                limit = maxBytes,
                flush = ::flushStandardStreams,
                attach = { pointStandardStreamsAt(it, constants) },
                discard = ::discardStandardStreams,
                notice = { _notice.value = it },
                report = ::reportOriginal,
            )
        } catch (error: Throwable) {
            val fallback = libc.open("/dev/null", constants.wronly)
            if (fallback < 0 || !redirectTo(fallback)) {
                reportOriginal("bitchat-tui: cannot redirect output: ${error.message.orEmpty()}")
                throw IllegalStateException("bitchat-tui cannot safely redirect output", error)
            }
            if (fallback > 2) libc.close(fallback)
            _notice.value = "Log unavailable (${error.message.orEmpty()}); output is discarded"
        }
    }

    fun rotateIfLarge() {
        val active = rotation ?: return
        try {
            active.tick()
        } catch (error: Throwable) {
            _notice.value = "Log rotation failed (${error.message.orEmpty()})"
            reportOriginal("bitchat-tui: log rotation failed: ${error.message.orEmpty()}")
        }
    }

    fun reportOriginal(message: String) {
        val bytes = (message + "\n").toByteArray(StandardCharsets.UTF_8)
        if (savedStderr >= 0) libc.write(savedStderr, bytes, bytes.size.toLong()) else System.err.println(message)
    }

    internal fun shouldRotate(size: Long): Boolean = size >= maxBytes
    internal fun rotationNames(name: String): List<String> = listOf("$name.2", "$name.1", name)
    /** Moves `log.1` to `log.2` and the log to `log.1`; returns where the log went. */
    internal fun rotateFiles(file: Path): Path {
        val first = file.resolveSibling(file.fileName.toString() + ".1")
        val second = file.resolveSibling(file.fileName.toString() + ".2")
        if (Files.exists(first)) Files.move(first, second, REPLACE_EXISTING)
        if (Files.exists(file)) Files.move(file, first, REPLACE_EXISTING)
        return first
    }

    /**
     * Opens [file] append-only, close-on-exec and owner-only from the start (no window with looser permissions) and
     * returns the descriptor; negative when it could not be opened.
     */
    internal fun openLog(file: Path, constants: Constants = Constants.forOs(System.getProperty("os.name"))): Int {
        val existed = Files.exists(file)
        val fd = libc.open(file.toString(), constants.wronly or constants.creat or constants.append or constants.cloexec, 0x180)
        if (fd >= 0 && existed) restrict(file, filePermissions)
        return fd
    }

    private fun pointStandardStreamsAt(file: Path, constants: Constants) {
        val fd = openLog(file, constants)
        check(fd >= 0) { "cannot open $file" }
        // dup2 replaces fd 1/2 in one step, which also closes whatever file they pointed at before (rotation).
        try {
            check(redirectTo(fd)) { "cannot redirect standard streams" }
        } finally {
            if (fd > 2) libc.close(fd)
        }
    }

    private fun discardStandardStreams() {
        val constants = Constants.forOs(System.getProperty("os.name"))
        val fd = libc.open("/dev/null", constants.wronly)
        check(fd >= 0) { "cannot open /dev/null" }
        try {
            check(redirectTo(fd)) { "cannot redirect standard streams" }
        } finally {
            if (fd > 2) libc.close(fd)
        }
    }

    private fun flushStandardStreams() {
        if (redirectOut) System.out.flush()
        if (redirectErr) System.err.flush()
    }

    private fun redirectTo(fd: Int): Boolean {
        flushStandardStreams()
        if (redirectOut && libc.dup2(fd, 1) < 0) return false
        if (redirectErr && libc.dup2(fd, 2) < 0) return false
        return true
    }

    private fun restrict(path: Path, permissions: Set<PosixFilePermission>) {
        try { Files.setPosixFilePermissions(path, permissions) } catch (_: UnsupportedOperationException) { }
    }

    private fun defaultDirectory(): Path = Path.of(System.getProperty("user.home"), ".bitchat")
    private val directoryPermissions = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
    private val filePermissions = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)

    /** The libc `open`/`fcntl` numbers that differ between macOS and Linux (x86_64 and arm64 share the Linux ones). */
    internal data class Constants(
        val wronly: Int,
        val rdwr: Int,
        val creat: Int,
        val noctty: Int,
        val append: Int,
        val cloexec: Int,
        val dupFdCloexec: Int,
    ) {
        companion object {
            val macos = Constants(wronly = 0x1, rdwr = 0x2, creat = 0x200, noctty = 0x20000, append = 0x8, cloexec = 0x1000000, dupFdCloexec = 67)
            val linux = Constants(wronly = 0x1, rdwr = 0x2, creat = 0x40, noctty = 0x100, append = 0x400, cloexec = 0x80000, dupFdCloexec = 1030)

            fun forOs(name: String): Constants = when {
                name.contains("mac", ignoreCase = true) -> macos
                name.contains("linux", ignoreCase = true) -> linux
                else -> error("unsupported OS for terminal logging: '$name' (supported: macOS, Linux)")
            }
        }
    }
}
