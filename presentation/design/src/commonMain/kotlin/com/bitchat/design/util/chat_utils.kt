package com.bitchat.design.util

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import com.bitchat.design.BASE_FONT_SIZE
import com.bitchat.domain.chat.model.BitchatMessage
import io.ktor.utils.io.core.*
import kotlin.time.Instant

const val MAX_NICKNAME_LENGTH: Int = 15

private fun formatTimestamp(instant: Instant): String {
    val totalSeconds = instant.epochSeconds
    val hours = (totalSeconds / 3600) % 24
    val minutes = (totalSeconds / 60) % 60
    val seconds = totalSeconds % 60

    // Pad with zeros
    val h = hours.toString().padStart(2, '0')
    val m = minutes.toString().padStart(2, '0')
    val s = seconds.toString().padStart(2, '0')

    return "$h:$m:$s"
}

fun truncateNickname(name: String, maxLen: Int = MAX_NICKNAME_LENGTH): String {
    return if (name.length <= maxLen) name else name.take(maxLen)
}

@Composable
fun formatMessageAsAnnotatedString(
    message: BitchatMessage,
    currentUserNickname: String,
    myPeerID: String,
): AnnotatedString {
    val builder = AnnotatedString.Builder()
    val isDark =
        MaterialTheme.colorScheme.background.red + MaterialTheme.colorScheme.background.green + MaterialTheme.colorScheme.background.blue < 1.5f

    val isSelf = message.senderPeerID == myPeerID ||
            message.sender == currentUserNickname ||
            message.sender.startsWith("$currentUserNickname#")

    if (message.sender != "system") {
        val baseColor = if (isSelf) {
            Color(0xFFFF9500)
        } else {
            getPeerColor(message, isDark)
        }

        val (baseName, suffix) = splitSuffix(message.sender)

        builder.pushStyle(
            SpanStyle(
                color = baseColor,
                fontSize = BASE_FONT_SIZE.sp,
                fontWeight = if (isSelf) FontWeight.Bold else FontWeight.Medium
            )
        )
        builder.append("<@")
        builder.pop()
        builder.pushStyle(
            SpanStyle(
                color = baseColor,
                fontSize = BASE_FONT_SIZE.sp,
                fontWeight = if (isSelf) FontWeight.Bold else FontWeight.Medium
            )
        )
        val nicknameStart = builder.length
        val truncatedBase = truncateNickname(baseName)
        builder.append(truncatedBase)
        val nicknameEnd = builder.length

        if (!isSelf) {
            builder.addStringAnnotation(
                tag = "nickname_click",
                annotation = (message.originalSender ?: message.sender),
                start = nicknameStart,
                end = nicknameEnd
            )
        }
        builder.pop()

        if (suffix.isNotEmpty()) {
            builder.pushStyle(
                SpanStyle(
                    color = baseColor.copy(alpha = 0.6f),
                    fontSize = BASE_FONT_SIZE.sp,
                    fontWeight = if (isSelf) FontWeight.Bold else FontWeight.Medium
                )
            )
            builder.append(suffix)
            builder.pop()
        }

        builder.pushStyle(
            SpanStyle(
                color = baseColor,
                fontSize = BASE_FONT_SIZE.sp,
                fontWeight = if (isSelf) FontWeight.Bold else FontWeight.Medium
            )
        )
        builder.append("> ")
        builder.pop()

        appendIOSFormattedContent(builder, message.content, message.mentions, currentUserNickname, baseColor, isSelf, isDark)

        builder.pushStyle(
            SpanStyle(
                color = Color.Gray.copy(alpha = 0.7f),
                fontSize = (BASE_FONT_SIZE - 4).sp
            )
        )
        builder.append(" [${formatTimestamp(message.timestamp)}]")
        message.powDifficulty?.let { bits ->
            if (bits > 0) {
                builder.append(" ⛨${bits}b")
            }
        }
        builder.pop()

    } else {
        builder.pushStyle(
            SpanStyle(
                color = Color.Gray,
                fontSize = (BASE_FONT_SIZE - 2).sp,
                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
            )
        )
        builder.append("* ${message.content} *")
        builder.pop()

        builder.pushStyle(
            SpanStyle(
                color = Color.Gray.copy(alpha = 0.5f),
                fontSize = (BASE_FONT_SIZE - 4).sp
            )
        )
        builder.append(" [${formatTimestamp(message.timestamp)}]")
        builder.pop()
    }

    return builder.toAnnotatedString()
}

@Composable
fun formatMessageHeaderAnnotatedString(
    message: BitchatMessage,
    currentUserNickname: String,
    myPeerID: String,
): AnnotatedString {
    val builder = AnnotatedString.Builder()
    val isDark =
        MaterialTheme.colorScheme.background.red + MaterialTheme.colorScheme.background.green + MaterialTheme.colorScheme.background.blue < 1.5f

    val isSelf = message.senderPeerID == myPeerID ||
            message.sender == currentUserNickname ||
            message.sender.startsWith("$currentUserNickname#")

    if (message.sender != "system") {
        val baseColor = if (isSelf) Color(0xFFFF9500) else getPeerColor(message, isDark)
        val (baseName, suffix) = splitSuffix(message.sender)

        // "<@"
        builder.pushStyle(
            SpanStyle(
                color = baseColor,
                fontSize = BASE_FONT_SIZE.sp,
                fontWeight = if (isSelf) FontWeight.Bold else FontWeight.Medium
            )
        )
        builder.append("<@")
        builder.pop()

        builder.pushStyle(
            SpanStyle(
                color = baseColor,
                fontSize = BASE_FONT_SIZE.sp,
                fontWeight = if (isSelf) FontWeight.Bold else FontWeight.Medium
            )
        )
        val nicknameStart = builder.length
        builder.append(truncateNickname(baseName))
        val nicknameEnd = builder.length
        if (!isSelf) {
            builder.addStringAnnotation(
                tag = "nickname_click",
                annotation = (message.originalSender ?: message.sender),
                start = nicknameStart,
                end = nicknameEnd
            )
        }
        builder.pop()

        if (suffix.isNotEmpty()) {
            builder.pushStyle(
                SpanStyle(
                    color = baseColor.copy(alpha = 0.6f),
                    fontSize = BASE_FONT_SIZE.sp,
                    fontWeight = if (isSelf) FontWeight.Bold else FontWeight.Medium
                )
            )
            builder.append(suffix)
            builder.pop()
        }

        builder.pushStyle(
            SpanStyle(
                color = baseColor,
                fontSize = BASE_FONT_SIZE.sp,
                fontWeight = if (isSelf) FontWeight.Bold else FontWeight.Medium
            )
        )
        builder.append(">")
        builder.pop()

        builder.pushStyle(
            SpanStyle(
                color = Color.Gray.copy(alpha = 0.7f),
                fontSize = (BASE_FONT_SIZE - 4).sp
            )
        )
        builder.append("  [${formatTimestamp(message.timestamp)}]")
        message.powDifficulty?.let { bits ->
            if (bits > 0) builder.append(" ⛨${bits}b")
        }
        builder.pop()
    } else {
        builder.pushStyle(
            SpanStyle(
                color = Color.Gray,
                fontSize = (BASE_FONT_SIZE - 2).sp,
                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
            )
        )
        builder.append("* ${message.content} *")
        builder.pop()
        builder.pushStyle(
            SpanStyle(
                color = Color.Gray.copy(alpha = 0.5f),
                fontSize = (BASE_FONT_SIZE - 4).sp
            )
        )
        builder.append(" [${formatTimestamp(message.timestamp)}]")
        builder.pop()
    }

    return builder.toAnnotatedString()
}

fun getPeerColor(message: BitchatMessage, isDark: Boolean): Color {
    val seed = when {
        message.senderPeerID?.startsWith("nostr:") == true || message.senderPeerID?.startsWith("nostr_") == true -> {
            // For Nostr peers, use the full key if available, otherwise the peer ID
            "nostr:${message.senderPeerID?.lowercase()}"
        }

        message.senderPeerID?.length == 16 -> {
            // For ephemeral peer IDs, try to get stable Noise key, fallback to peer ID
            "noise:${message.senderPeerID?.lowercase()}"
        }

        message.senderPeerID?.length == 64 -> {
            // This is already a stable Noise key
            "noise:${message.senderPeerID?.lowercase()}"
        }

        else -> {
            // Fallback to sender name
            message.sender.lowercase()
        }
    }

    return colorForPeerSeed(seed, isDark)
}

fun colorForPeerSeed(seed: String, isDark: Boolean): Color {
    // djb2 hash algorithm (matches iOS implementation)
    var hash = 5381UL
    for (byte in seed.toByteArray()) {
        hash = ((hash shl 5) + hash) + byte.toULong()
    }

    var hue = (hash % 360UL).toDouble() / 360.0

    val orange = 30.0 / 360.0
    if (kotlin.math.abs(hue - orange) < 0.05) {
        hue = (hue + 0.12) % 1.0
    }

    val saturation = if (isDark) 0.50 else 0.70
    val brightness = if (isDark) 0.85 else 0.35

    return Color.hsv(
        hue = (hue * 360).toFloat(),
        saturation = saturation.toFloat(),
        value = brightness.toFloat()
    )
}

fun splitSuffix(name: String): Pair<String, String> {
    if (name.length < 5) return Pair(name, "")

    val suffix = name.takeLast(5)
    if (suffix.startsWith("#") && suffix.drop(1).all {
            it.isDigit() || it.lowercaseChar() in 'a'..'f'
        }) {
        val base = name.dropLast(5)
        return Pair(base, suffix)
    }

    return Pair(name, "")
}

fun buildMentionInsertionText(
    currentText: String,
    fullSenderName: String,
    isLocationChannel: Boolean
): String {
    val (baseName, hashSuffix) = splitSuffix(fullSenderName)
    val mentionText = if (isLocationChannel && hashSuffix.isNotEmpty()) {
        "@$baseName$hashSuffix"
    } else {
        "@$baseName"
    }

    return when {
        currentText.isEmpty() -> "$mentionText "
        currentText.endsWith(" ") -> "$currentText$mentionText "
        else -> "$currentText $mentionText "
    }
}

private fun appendIOSFormattedContent(
    builder: AnnotatedString.Builder,
    content: String,
    mentions: List<String>?,
    currentUserNickname: String,
    baseColor: Color,
    isSelf: Boolean,
    isDark: Boolean
) {
    // allow optional '#abcd' suffix in mentions
    val hashtagPattern = "#([a-zA-Z0-9_]+)".toRegex()
    val mentionPattern = "@([\\p{L}0-9_]+(?:#[a-fA-F0-9]{4})?)".toRegex()

    val hashtagMatches = hashtagPattern.findAll(content).toList()
    val mentionMatches = mentionPattern.findAll(content).toList()

    val mentionRanges = mentionMatches.map { it.range }
    fun overlapsMention(range: IntRange): Boolean {
        return mentionRanges.any { mentionRange ->
            range.first < mentionRange.last && range.last > mentionRange.first
        }
    }

    val allMatches = mutableListOf<Pair<IntRange, String>>()

    for (match in hashtagMatches) {
        if (!overlapsMention(match.range)) {
            allMatches.add(match.range to "hashtag")
        }
    }

    for (match in mentionMatches) {
        allMatches.add(match.range to "mention")
    }

    // TODO: Re-implement geohash and URL parsing with KMP-compatible approach
//    // Add standalone geohash matches (e.g., "#9q") that are not part of another word
//    // We use MessageSpecialParser to find exact ranges; then merge with existing ranges avoiding overlaps
//    val geoMatches = MessageSpecialParser.findStandaloneGeohashes(content)
//    for (gm in geoMatches) {
//        val range = gm.start until gm.endExclusive
//        if (!overlapsMention(range)) {
//            allMatches.add(range to "geohash")
//        }
//    }
//
//    // Add URL matches (http/https/www/bare domains). Exclude overlaps with mentions.
//    val urlMatches = MessageSpecialParser.findUrls(content)
//    for (um in urlMatches) {
//        val range = um.start until um.endExclusive
//        if (!overlapsMention(range)) {
//            allMatches.add(range to "url")
//        }
//    }

    fun rangesOverlap(a: IntRange, b: IntRange): Boolean {
        return a.first < b.last && a.last > b.first
    }

    val urlRanges = allMatches.filter { it.second == "url" }.map { it.first }
    val geoRanges = allMatches.filter { it.second == "geohash" }.map { it.first }
    if (geoRanges.isNotEmpty() || urlRanges.isNotEmpty()) {
        val iterator = allMatches.listIterator()
        while (iterator.hasNext()) {
            val (range, type) = iterator.next()
            val overlapsGeo = geoRanges.any { rangesOverlap(range, it) }
            val overlapsUrl = urlRanges.any { rangesOverlap(range, it) }
            if ((type == "hashtag" && overlapsGeo) || (type == "geohash" && overlapsUrl)) iterator.remove()
        }
    }

    allMatches.sortBy { it.first.first }

    var lastEnd = 0
    val isMentioned = mentions?.contains(currentUserNickname) == true

    for ((range, type) in allMatches) {
        if (lastEnd < range.first) {
            val beforeText = content.substring(lastEnd, range.first)
            if (beforeText.isNotEmpty()) {
                builder.pushStyle(
                    SpanStyle(
                        color = baseColor,
                        fontSize = BASE_FONT_SIZE.sp,
                        fontWeight = if (isSelf) FontWeight.Bold else FontWeight.Normal
                    )
                )
                if (isMentioned) {
                    builder.pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                    builder.append(beforeText)
                    builder.pop()
                } else {
                    builder.append(beforeText)
                }
                builder.pop()
            }
        }

        val matchText = content.substring(range.first, range.last + 1)
        when (type) {
            "mention" -> {
                val mentionWithoutAt = matchText.removePrefix("@")
                val (mBase, mSuffix) = splitSuffix(mentionWithoutAt)

                val isMentionToMe = mBase == currentUserNickname
                val mentionColor = if (isMentionToMe) Color(0xFFFF9500) else baseColor

                builder.pushStyle(
                    SpanStyle(
                        color = mentionColor,
                        fontSize = BASE_FONT_SIZE.sp,
                        fontWeight = if (isSelf) FontWeight.Bold else FontWeight.SemiBold
                    )
                )
                builder.append("@")
                builder.pop()

                builder.pushStyle(
                    SpanStyle(
                        color = mentionColor,
                        fontSize = BASE_FONT_SIZE.sp,
                        fontWeight = if (isSelf) FontWeight.Bold else FontWeight.SemiBold
                    )
                )
                builder.append(truncateNickname(mBase))
                builder.pop()

                if (mSuffix.isNotEmpty()) {
                    builder.pushStyle(
                        SpanStyle(
                            color = mentionColor.copy(alpha = 0.6f),
                            fontSize = BASE_FONT_SIZE.sp,
                            fontWeight = if (isSelf) FontWeight.Bold else FontWeight.SemiBold
                        )
                    )
                    builder.append(mSuffix)
                    builder.pop()
                }
            }

            "hashtag" -> {
                builder.pushStyle(
                    SpanStyle(
                        color = baseColor,
                        fontSize = BASE_FONT_SIZE.sp,
                        fontWeight = if (isSelf) FontWeight.Bold else FontWeight.Normal
                    )
                )
                if (isMentioned) {
                    builder.pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                    builder.append(matchText)
                    builder.pop()
                } else {
                    builder.append(matchText)
                }
                builder.pop()
            }

            else -> {
                when (type) {
                    "geohash" -> {
                        builder.pushStyle(
                            SpanStyle(
                                color = Color(0xFF007AFF),
                                fontSize = BASE_FONT_SIZE.sp,
                                fontWeight = if (isSelf) FontWeight.Bold else FontWeight.SemiBold,
                                textDecoration = TextDecoration.Underline
                            )
                        )
                        val start = builder.length
                        builder.append(matchText)
                        val end = builder.length
                        val geohash = matchText.removePrefix("#").lowercase()
                        builder.addStringAnnotation(
                            tag = "geohash_click",
                            annotation = geohash,
                            start = start,
                            end = end
                        )
                        builder.pop()
                    }
                    "url" -> {
                        builder.pushStyle(
                            SpanStyle(
                                color = Color(0xFF007AFF),
                                fontSize = BASE_FONT_SIZE.sp,
                                fontWeight = if (isSelf) FontWeight.Bold else FontWeight.SemiBold,
                                textDecoration = TextDecoration.Underline
                            )
                        )
                        val start = builder.length
                        builder.append(matchText)
                        val end = builder.length
                        builder.addStringAnnotation(
                            tag = "url_click",
                            annotation = matchText,
                            start = start,
                            end = end
                        )
                        builder.pop()
                    }
                    else -> {
                        builder.pushStyle(
                            SpanStyle(
                                color = baseColor,
                                fontSize = BASE_FONT_SIZE.sp,
                                fontWeight = if (isSelf) FontWeight.Bold else FontWeight.Normal
                            )
                        )
                        builder.append(matchText)
                        builder.pop()
                    }
                }
            }
        }

        lastEnd = range.last + 1
    }

    if (lastEnd < content.length) {
        val remainingText = content.substring(lastEnd)
        builder.pushStyle(
            SpanStyle(
                color = baseColor,
                fontSize = BASE_FONT_SIZE.sp,
                fontWeight = if (isSelf) FontWeight.Bold else FontWeight.Normal
            )
        )
        if (isMentioned) {
            builder.pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
            builder.append(remainingText)
            builder.pop()
        } else {
            builder.append(remainingText)
        }
        builder.pop()
    }
}
