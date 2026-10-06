package com.bitchat.mediautils

/** One path component and nothing else: not empty, not "." or "..", no '/', no '\\', no NUL character. */
fun isPlainFileName(name: String): Boolean {
    return name.isNotEmpty() &&
        name != "." &&
        name != ".." &&
        name.none { it == '/' || it == '\\' || it == 0.toChar() }
}

/**
 * The name a received file is saved under, made from the name its sender gave it.
 *
 * A received name comes from an unauthenticated peer and ends up both as a path on this device and as text in the
 * UI, so only letters, digits, `.`, `_` and `-` of ASCII are kept and everything else (separators, control
 * characters, spaces, other scripts) becomes `_`, as upstream's Android client does. Leading dots are dropped (no
 * `.`, no `..`, no hidden file) and so are trailing ones, an empty result is `file`, a name Windows reserves for a
 * device (`CON`, `NUL.txt`, `COM1`...) gets a `_` in front, and a long name is cut to 100 characters with its
 * extension kept. The result always satisfies [isPlainFileName].
 */
fun safeReceivedFileName(peerName: String): String {
    val mapped = buildString(peerName.length) {
        peerName.forEach { character ->
            if (
                character in 'a'..'z' ||
                character in 'A'..'Z' ||
                character in '0'..'9' ||
                character == '.' ||
                character == '_' ||
                character == '-'
            ) {
                append(character)
            } else {
                append('_')
            }
        }
    }.trim('.').ifEmpty { "file" }
    // Windows treats these as devices in every directory, whatever follows the first dot.
    val named = if (mapped.substringBefore('.').uppercase() in WINDOWS_DEVICE_NAMES) "_$mapped" else mapped

    if (named.length <= MAX_RECEIVED_FILE_NAME_LENGTH) return named

    val extension = named.substringAfterLast('.', missingDelimiterValue = "")
        .takeIf { it.isNotEmpty() }
        ?.let { ".$it" }
        ?.takeIf { it.length in 2..17 }

    return if (extension == null) {
        // The cut can land right after a dot; the name starts with something else, so this leaves something.
        named.take(MAX_RECEIVED_FILE_NAME_LENGTH).trimEnd('.')
    } else {
        named.take(MAX_RECEIVED_FILE_NAME_LENGTH - extension.length) + extension
    }
}

private const val MAX_RECEIVED_FILE_NAME_LENGTH = 100

private val WINDOWS_DEVICE_NAMES: Set<String> =
    setOf("CON", "PRN", "AUX", "NUL") + (0..9).flatMap { listOf("COM$it", "LPT$it") }
