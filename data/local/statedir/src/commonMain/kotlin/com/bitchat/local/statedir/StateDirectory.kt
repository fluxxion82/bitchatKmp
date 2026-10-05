@file:OptIn(ExperimentalForeignApi::class)

package com.bitchat.local.statedir

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import platform.posix.EEXIST
import platform.posix.ENOENT
import platform.posix.O_CLOEXEC
import platform.posix.O_DIRECTORY
import platform.posix.O_NOFOLLOW
import platform.posix.O_RDONLY
import platform.posix.close
import platform.posix.errno
import platform.posix.fchmod
import platform.posix.fstat
import platform.posix.geteuid
import platform.posix.getenv
import platform.posix.mkdir
import platform.posix.open
import platform.posix.stat
import platform.posix.strerror

/** Why there is no state directory this app may use. The message names the path and is meant to be read by a person. */
class StateDirectoryException(message: String) : RuntimeException(message)

/**
 * Where the Linux apps keep their state, and whether that place can be trusted: `$HOME/.bitchat` and the
 * directories under it (`prefs` holds the identity keys in plain text, `settings`, `tor`, `data`, received
 * files). Every module that writes there asks here, so there is one answer.
 *
 * Two rules. There is no fallback for `HOME`: unset, empty or relative means no state directory, and the
 * caller refuses to start. (`/tmp` was the fallback once: a place where another user can make `.bitchat`
 * first, and one that is emptied at boot.) And a directory is only used when it is a directory of its own,
 * owned by the user this app runs as and writable by nobody else; whoever owns a directory can rename,
 * replace or delete the files in it, whatever the files' own modes say.
 *
 * What is not checked is the directories above: a `HOME` in which other users can rename entries is not
 * something this can make safe.
 */
object StateDirectory {
    /** HOME, when it names a place: throws when it is unset, empty or not an absolute path. Touches no file. */
    fun home(): String = homeFrom(getenv("HOME")?.toKString())

    /** `$HOME/.bitchat`. Nothing is created or checked. Throws as [home] does. */
    fun path(): String = "${home()}/.bitchat"

    /**
     * Makes `$HOME/.bitchat`, and then each directory of [below] under it, this user's own private directory
     * (see [ensureOwn]), parent first, and returns the last one's path; with no argument that is the state
     * directory itself. Throws when `HOME` cannot be used, when a name is not a [plain one][isPlainName]
     * (nothing is created then), or when a directory on the way is refused.
     */
    fun own(vararg below: String): String = ownBelow(path(), below.toList())

    /**
     * Creates [path] (mode 0700) when it is missing, and throws unless it then is a directory of its own, not
     * a link, that this user owns and that neither its group nor others can write. A directory of this user's
     * with a looser mode is tightened to 0700, as it always was. One that another user owns is refused and
     * left exactly as it is.
     */
    fun ensureOwn(path: String) {
        ensureOwn(path, geteuid())
    }

    /**
     * As [ensureOwn], but creates nothing: false when [path] does not exist, true when it is (now) this
     * user's own private directory, and a [StateDirectoryException] when it exists and is not.
     */
    fun ownIfPresent(path: String): Boolean = ownIfPresent(path, geteuid())

    /**
     * The path of `$HOME/.bitchat/<below...>` when the state directory and every directory of [below] exist
     * and are this user's own private directories ([ownIfPresent]); null when one of them is not there.
     * Creates nothing: for a reader that runs before the app has written anything. Throws as [own] does.
     */
    fun present(vararg below: String): String? = presentBelow(path(), below.toList())

    /** One path component and nothing else: not empty, not `.` or `..`, with no slash and no NUL character. */
    fun isPlainName(name: String): Boolean =
        name.isNotEmpty() && name != "." && name != ".." && '/' !in name && 0.toChar() !in name

    internal fun homeFrom(value: String?): String = when {
        value.isNullOrEmpty() -> throw StateDirectoryException(
            "HOME is not set, so there is no state directory (bitchat keeps its identity in \$HOME/.bitchat and nowhere else)",
        )
        !value.startsWith('/') -> throw StateDirectoryException(
            "HOME ($value) is not an absolute path, so there is no state directory",
        )
        else -> value
    }

    /*
     * The same calls with what the tests need to vary: [user] is the owner a directory must have, and
     * [tighten] is the chmod, so a test can stand in for a filesystem on which it changes nothing.
     */

    internal fun ensureOwn(path: String, user: UInt, tighten: (Int) -> Unit = ::restrictToOwner) {
        if (mkdir(path, MODE_0700.convert()) != 0 && errno != EEXIST) {
            throw StateDirectoryException("cannot create $path: ${lastError()}")
        }
        openAndCheck(path, user, tighten, absentIsFalse = false)
    }

    internal fun ownIfPresent(path: String, user: UInt, tighten: (Int) -> Unit = ::restrictToOwner): Boolean =
        openAndCheck(path, user, tighten, absentIsFalse = true)

    internal fun presentBelow(base: String, below: List<String>, user: UInt = geteuid()): String? {
        requirePlainNames(below)
        if (!ownIfPresent(base, user)) return null
        var current = base
        for (name in below) {
            current = "$current/$name"
            if (!ownIfPresent(current, user)) return null
        }
        return current
    }

    internal fun ownBelow(base: String, below: List<String>, user: UInt = geteuid()): String {
        requirePlainNames(below)
        ensureOwn(base, user)
        var current = base
        for (name in below) {
            current = "$current/$name"
            ensureOwn(current, user)
        }
        return current
    }

    /**
     * The check itself, on a descriptor: what is examined is the directory that was opened, whatever its
     * path leads to a moment later. Returns false only for a missing [path] when [absentIsFalse].
     */
    private fun openAndCheck(path: String, user: UInt, tighten: (Int) -> Unit, absentIsFalse: Boolean): Boolean {
        // O_NOFOLLOW: the name has to be a directory itself. A link, even to a directory this user owns,
        // is whatever the one who could put the link there chose.
        val descriptor = open(path, O_RDONLY or O_DIRECTORY or O_NOFOLLOW or O_CLOEXEC)
        if (descriptor < 0) {
            if (absentIsFalse && errno == ENOENT) return false
            throw StateDirectoryException(
                "$path cannot be opened as a directory of its own (a link is not accepted): ${lastError()}",
            )
        }
        try {
            // Ownership first, and nothing is changed before it: root must not chmod another user's
            // directory and then refuse it.
            val found = status(descriptor, path)
            if (found.uid != user) {
                throw StateDirectoryException(
                    "$path belongs to uid ${found.uid}, not to the user this app runs as (uid $user)",
                )
            }
            if (found.permissions != MODE_0700) tighten(descriptor)
            // Looked at again instead of believing fchmod: some filesystems (FAT, some FUSE mounts) report
            // success and change nothing. What decides is whether anyone else can write here now.
            val left = status(descriptor, path)
            if ((left.permissions and WRITABLE_BY_OTHERS) != 0) {
                throw StateDirectoryException(
                    "$path is writable by other users (mode ${left.permissions.toString(8).padStart(3, '0')}) " +
                        "and could not be made private",
                )
            }
            return true
        } finally {
            close(descriptor)
        }
    }

    private fun requirePlainNames(names: List<String>) {
        for (name in names) {
            if (!isPlainName(name)) throw StateDirectoryException("$name is not a plain directory name")
        }
    }

    /** `fchmod` to 0700. Its result is not looked at: [openAndCheck] looks at the directory instead. */
    private fun restrictToOwner(descriptor: Int) {
        fchmod(descriptor, MODE_0700.convert())
    }

    private fun status(descriptor: Int, path: String): DirectoryStatus = memScoped {
        val info = alloc<stat>()
        if (fstat(descriptor, info.ptr) != 0) {
            throw StateDirectoryException("cannot inspect $path: ${lastError()}")
        }
        val mode = info.st_mode.convert<Int>()
        if ((mode and FILE_TYPE_MASK) != DIRECTORY) {
            throw StateDirectoryException("$path is not a directory")
        }
        DirectoryStatus(info.st_uid, mode and PERMISSIONS)
    }

    private fun lastError(): String = strerror(errno)?.toKString() ?: "errno $errno"

    private data class DirectoryStatus(val uid: UInt, val permissions: Int)

    private const val MODE_0700 = 0x1C0
    private const val FILE_TYPE_MASK = 0xF000 // S_IFMT
    private const val DIRECTORY = 0x4000 // S_IFDIR
    private const val PERMISSIONS = 0x1FF // 0777
    private const val WRITABLE_BY_OTHERS = 0x12 // 0022
}
