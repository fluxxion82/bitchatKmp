package com.bitchat.tui

import com.bitchat.domain.chat.model.BitchatMessage
import com.bitchat.domain.chat.model.BitchatMessageType
import com.bitchat.domain.location.model.Channel
import com.bitchat.viewvo.theme.peerColorSeed
import com.jakewharton.mosaic.text.AnnotatedString
import com.jakewharton.mosaic.text.SpanStyle
import com.jakewharton.mosaic.text.buildAnnotatedString
import com.jakewharton.mosaic.text.withStyle
import com.jakewharton.mosaic.ui.TextStyle
import kotlin.time.Instant

/** A message time as `HH:mm` in UTC, as the Compose app shows it. 2.7 can pass a local-zone formatter. */
fun utcClockTime(instant: Instant): String {
    val secondsOfDay = instant.epochSeconds.mod(86_400L)
    val hours = (secondsOfDay / 3600).toString().padStart(2, '0')
    val minutes = (secondsOfDay / 60 % 60).toString().padStart(2, '0')
    return "$hours:$minutes"
}

/**
 * A chat title for [channel]: `#mesh`, `#9q8yy (city)`, `#tech`, `DM with bob`. Names in it come
 * from peers; the screens sanitize titles before drawing them.
 */
fun channelTitle(channel: Channel): String = when (channel) {
    Channel.Mesh -> "#mesh"
    is Channel.Location -> "#${channel.geohash} (${channel.level.displayName.lowercase()})"
    is Channel.NamedChannel -> "#${channel.channelName.removePrefix("#")}"
    is Channel.MeshDM -> "DM with ${channel.displayName ?: channel.peerID.take(12)}"
    is Channel.NostrDM -> "DM with ${channel.displayName ?: channel.peerID.take(12)}"
    is Channel.Meshtastic ->
        if (channel.nodeNum == null) "#meshtastic" else "DM with ${channel.displayName ?: "node ${channel.nodeNum}"}"
}

/** Whether [message] is the user's own, by peer ID or by nickname (with or without a `#abcd` suffix). */
internal fun isOwnMessage(message: BitchatMessage, nickname: String, myPeerId: String?): Boolean =
    (myPeerId != null && message.senderPeerID == myPeerId) ||
        message.sender == nickname ||
        message.sender.startsWith("$nickname#")

/** Bytes as `500B`, `34KB` or `1.5MB`. */
internal fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "${bytes}B"
    bytes < 1024 * 1024 -> "${(bytes + 512) / 1024}KB"
    else -> {
        val tenths = (bytes * 10 + 524_288) / 1_048_576
        "${tenths / 10}.${tenths % 10}MB"
    }
}

/**
 * What a media message shows instead of its content: `[image foo.jpg 34KB]`, `[voice 18KB]`,
 * `[file report.pdf 120KB]`; null for text. Image and voice content is a local path, so only its
 * last segment is shown; [size] is passed in because this module never touches the filesystem.
 * The names come from peers and are sanitized with the rest of the line.
 */
internal fun mediaPlaceholder(message: BitchatMessage, size: Long?): String? {
    fun sized(bytes: Long?) = bytes?.let { " " + formatSize(it) } ?: ""
    fun named(name: String) = if (name.isEmpty()) "" else " $name"
    return when (message.type) {
        BitchatMessageType.Image -> "[image${named(fileName(message.content))}${sized(size)}]"
        BitchatMessageType.Audio -> "[voice${sized(size)}]"
        BitchatMessageType.File -> {
            val packet = message.filePacket
            "[file${named(packet?.fileName ?: fileName(message.content))}${sized(packet?.fileSize ?: size)}]"
        }
        else -> null
    }
}

private fun fileName(path: String) = path.substringAfterLast('/').substringAfterLast('\\')

/**
 * The display lines of one message at [width] cells: `HH:mm <sender> body`, the time dim, the
 * sender in its colour (own messages bold yellow), later body lines indented by two cells, all
 * wrapped by cells. System messages (`sender == "system"`) are `HH:mm * body`, dim throughout.
 * Every peer string (sender, body, file names) goes through the sanitizer before it is measured.
 *
 * A message mined with proof of work ends in a dim [powMark], the way the Compose apps put their
 * shield after the timestamp. Only geohash messages ever carry one: proof of work is mined into the
 * Nostr event, so nothing on the mesh has it. The mark is ASCII, because the Linux console's font
 * has no shield and a missing glyph would take the wrong number of cells.
 */
internal fun messageLines(
    message: BitchatMessage,
    nickname: String,
    myPeerId: String?,
    width: Int,
    consoleSafe: Boolean,
    mediaSize: Long?,
    formatTime: (Instant) -> String,
    theme: TuiTheme = DarkTuiTheme,
): List<AnnotatedString> {
    val time = formatTime(message.timestamp)
    val body = mediaPlaceholder(message, mediaSize)?.let { listOf(displayText(it, consoleSafe)) }
        ?: sanitizePeerLines(message.content).map { if (consoleSafe) consoleSafe(it) else it }
    if (message.sender == SYSTEM_SENDER || message.type == BitchatMessageType.System) {
        val dim = SpanStyle(color = theme.dim, textStyle = TextStyle.Dim)
        return body.flatMapIndexed { index, line ->
            wrapCells(buildAnnotatedString { withStyle(dim) { append(if (index == 0) "$time * $line" else "  $line") } }, width)
        }
    }
    val nameStyle = if (isOwnMessage(message, nickname, myPeerId)) {
        SpanStyle(color = theme.own, textStyle = TextStyle.Bold)
    } else {
        SpanStyle(color = theme.peer(peerColorSeed(message.senderPeerID, message.sender)))
    }
    val name = displayText(message.sender, consoleSafe).truncateCells(MAX_SENDER_CELLS)
    val mark = powMark(message.powDifficulty)
    return body.flatMapIndexed { index, line ->
        val text = buildAnnotatedString {
            if (index == 0) {
                withStyle(SpanStyle(color = theme.dim, textStyle = TextStyle.Dim)) { append(time) }
                append(" ")
                withStyle(nameStyle) { append("<$name>") }
                append(" ")
            } else {
                append("  ")
            }
            append(line)
            // On the last line of the message, as in the Compose apps, so a wrapped message is
            // marked once rather than on every row.
            if (mark != null && index == body.lastIndex) {
                withStyle(SpanStyle(color = theme.dim, textStyle = TextStyle.Dim)) { append(mark) }
            }
        }
        wrapCells(text, width)
    }
}

/**
 * The proof-of-work mark for a message mined at [bits], or null for one that carries none. `pow16`
 * rather than a shield: the console font has no shield, and everything here is measured in cells.
 */
internal fun powMark(bits: Int?): String? = if (bits != null && bits > 0) " pow$bits" else null

/** The sender name bitchat uses for local status lines. */
internal const val SYSTEM_SENDER = "system"

/** Sender names longer than this are ellipsized. */
internal const val MAX_SENDER_CELLS = 20
