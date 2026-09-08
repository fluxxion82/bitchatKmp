package com.bitchat.local.prefs

/**
 * The on-disk shape of the embedded (Linux) preference files.
 *
 * One record per line, `key=value`, LF-terminated, first `=` separates. Values may contain
 * `=`; keys may not. The format is deliberately frozen: files written by every previous build
 * must keep reading back identically, and a later migration to an encrypted store has to be
 * able to read them one last time.
 *
 * The decoder is pure so it can be tested on any host, which matters because the only
 * production caller is Kotlin/Native `linuxArm64` and that cannot be executed on a developer
 * machine.
 *
 * ## Fidelity note
 *
 * The reader this replaced read the file in 4096-byte `fgets` chunks and called `String.trim()`
 * on each chunk before splitting. That had two consequences worth stating explicitly:
 *
 *  * Any record longer than 4095 bytes was cut in two. The tail became a separate "line", and
 *    was either dropped (no `=`) or injected as a junk key (base64 padding and JSON both
 *    contain `=`). The block list already exceeds that length.
 *  * A value's trailing whitespace and a key's leading whitespace were destroyed on the way in,
 *    but not on the way out - the in-memory map held whatever was `put`, so the same value read
 *    differently before and after a restart.
 *
 * [decode] reproduces what the *writer* emitted, byte for byte: it strips the record's
 * terminating newline and nothing else. On data that a previous build wrote this is identical
 * to the old reader's output, because everything that survived a load-store cycle was already
 * trimmed; it differs only for whitespace that the old reader would have silently eaten on the
 * next start.
 */
object FlatFileFormat {

    /** Serialises [entries] in iteration order. The result always ends in a newline, or is empty. */
    fun encode(entries: Map<String, String>): String = buildString {
        for ((key, value) in entries) {
            append(key)
            append('=')
            append(value)
            append('\n')
        }
    }

    /**
     * Parses the whole contents of one preference file.
     *
     * Never throws and never discards readable records: damage is collected in
     * [FlatFileContent.damage] so the caller can report it while still using what it got.
     */
    fun decode(text: String): FlatFileContent {
        val entries = LinkedHashMap<String, String>()
        val damage = mutableListOf<String>()

        if (text.isEmpty()) return FlatFileContent(entries, damage)

        // The writer terminates every record. A file that does not end in a newline was cut
        // short - a half-finished write, a full disk, or a power cut mid-save.
        if (!text.endsWith("\n")) {
            damage += "file does not end with a newline: the last record is truncated"
        }

        val lines = text.split('\n')
        val lastIndex = lines.lastIndex
        for ((index, line) in lines.withIndex()) {
            // Only the writer's own final newline is stripped, not a blank record.
            if (index == lastIndex && line.isEmpty()) break
            if (line.isEmpty()) continue

            val separator = line.indexOf('=')
            val lineNumber = index + 1
            when {
                separator < 0 ->
                    damage += "line $lineNumber: no '=' separator (${line.length} chars)"

                separator == 0 ->
                    damage += "line $lineNumber: empty key"

                else -> {
                    val key = line.substring(0, separator)
                    val value = line.substring(separator + 1)
                    if (entries.put(key, value) != null) {
                        damage += "line $lineNumber: duplicate key '$key'"
                    }
                }
            }
        }

        return FlatFileContent(entries, damage)
    }
}

/**
 * What [FlatFileFormat.decode] made of a file.
 *
 * @param entries every well-formed record, in file order, later records winning.
 * @param damage human-readable descriptions of anything that was not well formed. Empty means
 *   the file was intact. A non-empty list means the store must not be mistaken for a fresh one:
 *   a key that is missing from a damaged file may simply have been lost.
 */
data class FlatFileContent(
    val entries: Map<String, String>,
    val damage: List<String>,
) {
    val isDamaged: Boolean get() = damage.isNotEmpty()
}
