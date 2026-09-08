package com.bitchat.local.prefs

import com.russhwolf.settings.Settings
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.EEXIST
import platform.posix.EINTR
import platform.posix.ENOENT
import platform.posix.O_RDONLY
import platform.posix.chmod
import platform.posix.close
import platform.posix.errno
import platform.posix.fchmod
import platform.posix.fclose
import platform.posix.ferror
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fsync
import platform.posix.getenv
import platform.posix.mkdir
import platform.posix.mkstemp
import platform.posix.open
import platform.posix.rename
import platform.posix.strerror
import platform.posix.unlink
import platform.posix.write

/**
 * Raised when a preference file exists but cannot be read or written.
 *
 * Never raised for a file that is simply not there: that is a first run, and the store starts
 * empty. It is raised for everything else, because the alternative - quietly presenting an
 * empty store - lets the app mint a fresh identity on top of one that is still on disk.
 */
class PreferenceStoreIOException(
    message: String,
    val path: String,
) : RuntimeException(message)

/** Directories are 0700, files 0600. Nothing else has any business in `~/.bitchat`. */
private const val MODE_0700: UInt = 448u // 0o700
private const val MODE_0600: UInt = 384u // 0o600

@OptIn(ExperimentalForeignApi::class)
private fun posixError(action: String, path: String): PreferenceStoreIOException {
    val code = errno
    val reason = strerror(code)?.toKString() ?: "errno $code"
    return PreferenceStoreIOException("$action $path failed: $reason (errno $code)", path)
}

/**
 * Creates [path] with mode 0700.
 *
 * `mkdir`'s mode argument is masked by the process umask and is ignored outright when the
 * directory already exists, so the mode is set explicitly either way. Directories created by
 * older builds are therefore repaired on the next start.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun ensureDirectory(path: String) {
    if (mkdir(path, MODE_0700) != 0 && errno != EEXIST) {
        throw posixError("creating directory", path)
    }
    chmod(path, MODE_0700)
}

/**
 * Creates [path] if it is not there, and leaves its mode alone if it is.
 *
 * For directories this application needs but does not own - `$HOME/.config`, say. [ensureDirectory]
 * would `chmod 0700` an existing one, and quietly tightening a directory the user shares with
 * every other application on the box is not this code's business.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun ensureParentDirectory(path: String) {
    if (mkdir(path, MODE_0700) != 0 && errno != EEXIST) {
        throw posixError("creating directory", path)
    }
}

/**
 * Returns the whole file at [path] as text, or null when it has never been written.
 *
 * Extracted verbatim from `LinuxFileSettings`, which is its only other caller. Reading the whole
 * file in one go matters: the reader this replaced pulled 4096-byte `fgets` chunks and treated
 * each chunk as a line, so every record longer than that was cut in two.
 *
 * Bytes are decoded once, at the end, because a UTF-8 sequence can straddle two reads.
 */
// T7: replace with PosixFiles (open once, O_NOFOLLOW, checks against the fstat of that fd).
@OptIn(ExperimentalForeignApi::class)
internal fun readWholeFileOrNull(path: String): String? {
    val file = fopen(path, "rb")
    if (file == null) {
        if (errno == ENOENT) return null
        throw posixError("opening", path)
    }

    try {
        val chunks = mutableListOf<ByteArray>()
        var total = 0
        val buffer = ByteArray(64 * 1024)
        buffer.usePinned { pinned ->
            while (true) {
                val read = fread(pinned.addressOf(0), 1.convert(), buffer.size.convert(), file).toLong().toInt()
                if (read <= 0) break
                chunks += buffer.copyOf(read)
                total += read
            }
        }
        if (ferror(file) != 0) throw posixError("reading", path)

        val bytes = ByteArray(total)
        var offset = 0
        for (chunk in chunks) {
            chunk.copyInto(bytes, offset)
            offset += chunk.size
        }
        return bytes.decodeToString()
    } finally {
        fclose(file)
    }
}

/**
 * Writes [payload] to [path] atomically and durably: a fresh `mkstemp` file in the same
 * directory, `fchmod` 0600, the whole payload, `fsync`, `close`, `rename` over the target, then
 * `fsync` of the directory so the rename itself reaches the disk.
 *
 * Extracted verbatim from `LinuxFileSettings.saveToFile`; unchanged in behaviour. **The live
 * file is never moved aside first**, precisely because that would open a window in which the
 * only copy of an identity does not exist - which is how "absent file" came to be read as
 * "new device" in the first place.
 */
// T7: replace with PosixFiles.writeDurably.
@OptIn(ExperimentalForeignApi::class)
internal fun writeFileDurably(path: String, payload: ByteArray) {
    val directory = path.substringBeforeLast('/', ".").ifEmpty { "/" }

    memScoped {
        // mkstemp rewrites the template in place, so it needs a mutable buffer, and it
        // creates the file 0600 regardless of umask.
        val template = "$path.tmpXXXXXX".encodeToByteArray()
        val templateBuffer = allocArray<ByteVar>(template.size + 1)
        for (i in template.indices) templateBuffer[i] = template[i]
        templateBuffer[template.size] = 0

        val fd = mkstemp(templateBuffer)
        if (fd < 0) throw posixError("creating a temporary file next to", path)
        val tempPath = templateBuffer.toKString()

        try {
            // Explicit, so the mode does not depend on mkstemp's documented behaviour, and
            // so that renaming over an older 0664 file leaves 0600 behind.
            if (fchmod(fd, MODE_0600) != 0) throw posixError("setting the mode of", tempPath)

            if (payload.isNotEmpty()) {
                payload.usePinned { pinned ->
                    var written = 0
                    while (written < payload.size) {
                        val n = write(
                            fd,
                            pinned.addressOf(written),
                            (payload.size - written).convert(),
                        ).toLong()
                        if (n <= 0L) {
                            if (n < 0L && errno == EINTR) continue
                            throw posixError("writing", tempPath)
                        }
                        written += n.toInt()
                    }
                }
            }

            if (fsync(fd) != 0) throw posixError("flushing", tempPath)
        } catch (e: Throwable) {
            close(fd)
            unlink(tempPath)
            throw e
        }

        if (close(fd) != 0) {
            unlink(tempPath)
            throw posixError("closing", tempPath)
        }

        // Atomic: readers see the whole old file or the whole new one.
        if (rename(tempPath, path) != 0) {
            val failure = posixError("renaming $tempPath over", path)
            unlink(tempPath)
            throw failure
        }

        // The rename itself has to reach the disk, or a power cut can still lose it.
        val dirFd = open(directory, O_RDONLY)
        if (dirFd >= 0) {
            fsync(dirFd)
            close(dirFd)
        }
    }
}

/**
 * Linux implementation of EncryptionSettingsFactory.
 *
 * Uses file-based storage with POSIX file permissions. For embedded/headless Linux systems
 * this provides persistence without requiring a keychain or credential store.
 *
 * Note: the contents are NOT encrypted, unlike Apple's Keychain or Android's
 * EncryptedSharedPreferences. The files are 0600 inside a 0700 directory, which keeps other
 * local users out but not root and not anyone holding the SD card.
 */
@OptIn(ExperimentalForeignApi::class)
class LinuxEncryptionSettingsFactory : EncryptionSettingsFactory {

    private val prefsDir: String by lazy {
        val home = getenv("HOME")?.toKString() ?: "/tmp"
        val baseDir = "$home/.bitchat"
        val dir = "$baseDir/prefs"
        ensureDirectory(baseDir)
        ensureDirectory(dir)
        dir
    }

    // Cache of loaded settings to avoid re-reading files
    private val settingsCache = mutableMapOf<String, LinuxFileSettings>()

    override fun createEncrypted(name: String): Settings {
        return settingsCache.getOrPut(name) {
            LinuxFileSettings("$prefsDir/$name.prefs")
        }
    }
}

/**
 * File-based [Settings] for Linux, storing key-value pairs in the [FlatFileFormat] text format.
 *
 * Reads happen once, at construction, into an in-memory map; every mutation rewrites the whole
 * file. Two properties matter and neither held before:
 *
 *  * **The whole file is read.** The previous reader pulled 4096-byte chunks through `fgets`
 *    and treated each chunk as a line, so any record longer than that was truncated and its
 *    tail was either dropped or injected as a junk key. The block list already exceeds 4096
 *    bytes.
 *  * **Writes are atomic and durable.** The previous writer opened the live file with `"w"`,
 *    which truncates it, and never called `fsync`. A power cut between the truncate and the
 *    write left the identity gone; a clean shutdown left it unflushed. Now the payload goes to
 *    a fresh `mkstemp` file in the same directory, is fsynced, and is `rename`d over the
 *    target - `rename` is atomic, so a reader sees either the old file or the new one and
 *    never a partial or absent one. The live file is never moved aside first, precisely
 *    because that would open a window where the only copy does not exist.
 */
@OptIn(ExperimentalForeignApi::class)
class LinuxFileSettings(private val filepath: String) : Settings, HealthReportingSettings {
    private val data = mutableMapOf<String, String>()

    override var storeDamage: List<String> = emptyList()
        private set

    init {
        loadFromFile()
    }

    /** How this store presented itself at startup. Absent and empty both count as a first run. */
    fun storeState(): PreferenceStoreState =
        PreferenceStoreState.of(damage = storeDamage, isEmpty = data.isEmpty())

    private fun loadFromFile() {
        val text = readWholeFile() ?: return // absent: a first run, nothing to report
        val content = FlatFileFormat.decode(text)
        data.putAll(content.entries)
        storeDamage = content.damage
        if (content.isDamaged) {
            // Loud, because a damaged store means a missing key might have been lost rather
            // than never written, and callers must not treat that as a first run.
            println("LinuxFileSettings: $filepath is damaged, ${content.entries.size} record(s) recovered")
            content.damage.forEach { println("LinuxFileSettings:   $it") }
        }
    }

    private fun readWholeFile(): String? = readWholeFileOrNull(filepath)

    private fun saveToFile() {
        writeFileDurably(filepath, FlatFileFormat.encode(data).encodeToByteArray())

        // The file that is now on disk is exactly what is in memory.
        storeDamage = emptyList()
    }

    override val keys: Set<String> get() = data.keys.toSet()
    override val size: Int get() = data.size

    override fun clear() {
        data.clear()
        saveToFile()
    }

    override fun remove(key: String) {
        data.remove(key)
        saveToFile()
    }

    override fun hasKey(key: String): Boolean = data.containsKey(key)

    override fun putInt(key: String, value: Int) {
        data[key] = value.toString()
        saveToFile()
    }

    override fun getInt(key: String, defaultValue: Int): Int =
        data[key]?.toIntOrNull() ?: defaultValue

    override fun getIntOrNull(key: String): Int? = data[key]?.toIntOrNull()

    override fun putLong(key: String, value: Long) {
        data[key] = value.toString()
        saveToFile()
    }

    override fun getLong(key: String, defaultValue: Long): Long =
        data[key]?.toLongOrNull() ?: defaultValue

    override fun getLongOrNull(key: String): Long? = data[key]?.toLongOrNull()

    override fun putString(key: String, value: String) {
        data[key] = value
        saveToFile()
    }

    override fun getString(key: String, defaultValue: String): String =
        data[key] ?: defaultValue

    override fun getStringOrNull(key: String): String? = data[key]

    override fun putFloat(key: String, value: Float) {
        data[key] = value.toString()
        saveToFile()
    }

    override fun getFloat(key: String, defaultValue: Float): Float =
        data[key]?.toFloatOrNull() ?: defaultValue

    override fun getFloatOrNull(key: String): Float? = data[key]?.toFloatOrNull()

    override fun putDouble(key: String, value: Double) {
        data[key] = value.toString()
        saveToFile()
    }

    override fun getDouble(key: String, defaultValue: Double): Double =
        data[key]?.toDoubleOrNull() ?: defaultValue

    override fun getDoubleOrNull(key: String): Double? = data[key]?.toDoubleOrNull()

    override fun putBoolean(key: String, value: Boolean) {
        data[key] = value.toString()
        saveToFile()
    }

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        data[key]?.toBooleanStrictOrNull() ?: defaultValue

    override fun getBooleanOrNull(key: String): Boolean? = data[key]?.toBooleanStrictOrNull()
}
