package com.bitchat.domain.base

/**
 * Whether logs may show message bodies: chat and DM text, LoRa and Meshtastic payloads, relay
 * frames. Off in every build unless an app entry point opts in from [ENV_VAR] (desktop and the
 * embedded apps do; Android never does), because the logs end up in places a transcript must not:
 * the systemd journal, `~/.bitchat/tui.log`, logcat. Off, log lines carry lengths and IDs only.
 *
 * Set once at startup, before anything logs.
 */
object LogPolicy {
    /** `BITCHAT_LOG_BODIES=1` (or `true`) turns body logging on; anything else leaves it off. */
    const val ENV_VAR = "BITCHAT_LOG_BODIES"

    var messageBodies: Boolean = false
        private set

    /** Applies the value of [ENV_VAR] (null: unset). */
    fun configure(value: String?) {
        messageBodies = value == "1" || value.equals("true", ignoreCase = true)
    }
}

/**
 * [body] as a log line may show it: `<N chars>`, or, with [LogPolicy.messageBodies], the text in
 * quotes, cut after [preview] characters.
 */
fun logBody(body: String?, preview: Int = Int.MAX_VALUE): String = when {
    body == null -> "<null>"
    !LogPolicy.messageBodies -> "<${body.length} chars>"
    body.length > preview -> "\"${body.take(preview)}...\" (${body.length} chars)"
    else -> "\"$body\""
}

/** [bytes] as a log line may show them: `<N bytes>`, or [dump] of them with [LogPolicy.messageBodies]. */
inline fun logBytes(bytes: ByteArray, dump: () -> String): String = logBytes(bytes.size, dump)

/** A payload of [size] bytes as a log line may show it: `<N bytes>`, or [dump] with [LogPolicy.messageBodies]. */
inline fun logBytes(size: Int, dump: () -> String): String =
    if (LogPolicy.messageBodies) dump() else "<$size bytes>"

/**
 * A media file's path or name as a log line may show it: `<file>`, or the path itself with
 * [LogPolicy.messageBodies]. Names come from peers or from the user's own files and say what was sent.
 */
fun logPath(path: String?): String = when {
    path == null -> "<none>"
    LogPolicy.messageBodies -> path
    else -> "<file>"
}

/**
 * An exception as a log line may show it: its class, and its message (file operations put paths
 * in it) only with [LogPolicy.messageBodies].
 */
fun logError(error: Throwable): String {
    val name = error::class.simpleName ?: "error"
    return if (LogPolicy.messageBodies) "$name: ${error.message}" else name
}

/** Prints [error]'s stack trace, which carries its message, only with [LogPolicy.messageBodies]. */
fun logStackTrace(error: Throwable) {
    if (LogPolicy.messageBodies) error.printStackTrace()
}
