package com.bitchat.local.identity

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.pointed
import kotlinx.cinterop.toKString
import platform.posix.ENOENT
import platform.posix.closedir
import platform.posix.errno
import platform.posix.opendir
import platform.posix.readdir
import platform.posix.set_posix_errno
import platform.posix.strerror

/**
 * Lists the identity domain with `opendir`/`readdir`, reading no file contents at all.
 *
 * ## The reading is taken in the constructor, and never again
 *
 * This is not an optimisation, it is the correctness property. The application's own first write
 * makes the domain inhabited, so a scan taken after the first store has been built would see the
 * application's own footprints and refuse to create the *second* identity component of a genuine
 * first run - leaving a brand new device permanently unable to have an identity at all. The
 * embedded `localModule` therefore registers this as `createdAtStart`, so the reading is taken
 * while Koin builds the graph, before any preference file exists.
 *
 * ## "Does not exist" and "could not be listed" are different answers
 *
 * `ENOENT` is positive evidence of absence and contributes to a first-run verdict. Anything else
 * - `EACCES`, `ENOTDIR`, `EIO` - is evidence of nothing, and makes the whole domain
 * [DomainVerdict.Indeterminate]. That distinction is the entire value of this class; everything
 * else here is bookkeeping.
 */
@OptIn(ExperimentalForeignApi::class)
class LinuxDomainInspector(
    private val directories: List<String> = LinuxIdentityPaths.domain,
) : DomainInspector {

    private val verdict: DomainVerdict = DomainScan.verdict(directories.map(::list))

    override fun scan(): DomainVerdict = verdict

    private fun list(path: String): DirectoryListing {
        val dir = opendir(path)
        if (dir == null) {
            val code = errno
            if (code == ENOENT) return DirectoryListing.absent(path)
            return DirectoryListing.unlistable(path, describe(code))
        }

        try {
            val names = mutableListOf<String>()
            while (true) {
                // readdir returns null both at the end of the directory and on an error, and
                // tells them apart only through errno. Reading a partial listing as a complete
                // one is exactly the mistake this class exists to avoid.
                set_posix_errno(0)
                val entry = readdir(dir) ?: break
                val code = errno
                if (code != 0) return DirectoryListing.unlistable(path, describe(code))
                names += entry.pointed.d_name.toKString()
            }
            return DirectoryListing.listed(path, names)
        } finally {
            closedir(dir)
        }
    }

    private fun describe(code: Int): String =
        "${strerror(code)?.toKString() ?: "unknown error"} (errno $code)"
}
