package com.bitchat.tor

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * Deletes the empty files in Arti's `<dataDir>/cache/dir_blobs` and returns how many it removed.
 *
 * A power cut during a consensus save leaves a file the cache database lists but with nothing in it.
 * Arti fetches a missing file again, and never finishes starting on an empty one. `dir.lock` is
 * legitimately empty and lives outside `dir_blobs`, so it is never looked at. Symbolic links are
 * left alone. Never throws.
 */
internal fun removeEmptyArtiCacheFiles(
    dataDir: String,
    delete: (Path) -> Unit = SystemFileSystem::delete,
    log: (String) -> Unit = ::println,
): Int {
    val blobs = Path(dataDir, "cache", "dir_blobs")
    val (resolvedBlobs, entries) = try {
        if (!SystemFileSystem.exists(blobs)) return 0
        SystemFileSystem.resolve(blobs) to SystemFileSystem.list(blobs)
    } catch (e: Exception) {
        logQuietly(log, "Could not list $blobs to look for empty Arti cache files: ${e.message}")
        return 0
    }

    var removed = 0
    for (entry in entries) {
        try {
            val metadata = SystemFileSystem.metadataOrNull(entry) ?: continue
            if (!metadata.isRegularFile || metadata.size != 0L) continue
            // The metadata above follows links; only a file that really lives in dir_blobs goes.
            if (SystemFileSystem.resolve(entry) != Path(resolvedBlobs, entry.name)) continue
            delete(entry)
            removed += 1
        } catch (e: Exception) {
            logQuietly(log, "Could not remove empty Arti cache file $entry: ${e.message}")
        }
    }
    if (removed > 0) {
        logQuietly(log, "Removed $removed empty file(s) from $blobs; Arti downloads them again.")
    }
    return removed
}

/** A log sink that throws must not stop the sweep. */
private fun logQuietly(log: (String) -> Unit, line: String) {
    try {
        log(line)
    } catch (_: Exception) {
    }
}
