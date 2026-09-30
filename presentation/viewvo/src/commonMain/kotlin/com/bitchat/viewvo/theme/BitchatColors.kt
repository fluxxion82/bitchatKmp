package com.bitchat.viewvo.theme

import kotlin.math.abs

/**
 * bitchat's colours, as plain ARGB values, so the Compose apps and the terminal UI draw the same
 * ones. A phosphor terminal: green on near-black in the dark, a darker green on near-white in the
 * light, with red for errors.
 */
data class BitchatPalette(
    val primary: Long,
    val onPrimary: Long,
    val secondary: Long,
    val onSecondary: Long,
    val background: Long,
    val onBackground: Long,
    val surface: Long,
    val onSurface: Long,
    val error: Long,
    val onError: Long,
)

val DarkPalette = BitchatPalette(
    primary = 0xFF39FF14,
    onPrimary = 0xFF000000,
    secondary = 0xFF2ECB10,
    onSecondary = 0xFF000000,
    background = 0xFF000000,
    onBackground = 0xFF39FF14,
    surface = 0xFF111111,
    onSurface = 0xFF39FF14,
    error = 0xFFFF5555,
    onError = 0xFF000000,
)

val LightPalette = BitchatPalette(
    primary = 0xFF008000,
    onPrimary = 0xFFFFFFFF,
    secondary = 0xFF006600,
    onSecondary = 0xFFFFFFFF,
    background = 0xFFFFFFFF,
    onBackground = 0xFF008000,
    surface = 0xFFF8F8F8,
    onSurface = 0xFF008000,
    error = 0xFFCC0000,
    onError = 0xFFFFFFFF,
)

/** The colour of one's own messages, in both themes. */
const val OWN_MESSAGE_COLOR: Long = 0xFFFF9500

/** The mesh, wherever it is named or counted: blue. */
const val MESH_COLOR: Long = 0xFF007AFF

/** A location (geohash) channel, wherever it is named or counted: green. */
const val GEOHASH_COLOR: Long = 0xFF00C851

/** LoRa, and private chats: the same orange as one's own messages. */
const val LORA_COLOR: Long = OWN_MESSAGE_COLOR

/** A colour as hue (0-360), saturation and value (0-1), which is how a peer's colour is chosen. */
data class Hsv(val hue: Float, val saturation: Float, val value: Float)

/**
 * What a peer's name is coloured by: their Nostr or Noise key when it is known, else the name they
 * are shown under, lower case. The same person keeps a colour across messages, and across apps.
 */
fun peerColorSeed(senderPeerID: String?, sender: String): String = when {
    senderPeerID == null -> sender.lowercase()
    senderPeerID.startsWith("nostr:") || senderPeerID.startsWith("nostr_") -> "nostr:${senderPeerID.lowercase()}"
    senderPeerID.length == 16 || senderPeerID.length == 64 -> "noise:${senderPeerID.lowercase()}"
    else -> sender.lowercase()
}

/**
 * The colour for [seed]: a hue from the djb2 hash of it (as the iOS app does), at a saturation and
 * value that read on a dark or light background. Hues close to orange are nudged away, because
 * that is the colour of one's own messages.
 */
fun peerColorHsv(seed: String, isDark: Boolean): Hsv {
    var hash = 5381UL
    for (byte in seed.encodeToByteArray()) {
        hash = ((hash shl 5) + hash) + byte.toInt().and(0xFF).toULong()
    }

    var hue = (hash % 360UL).toDouble() / 360.0

    val orange = 30.0 / 360.0
    if (abs(hue - orange) < 0.05) {
        hue = (hue + 0.12) % 1.0
    }

    val saturation = if (isDark) 0.50 else 0.70
    val brightness = if (isDark) 0.85 else 0.35

    return Hsv((hue * 360).toFloat(), saturation.toFloat(), brightness.toFloat())
}

/** This colour as 8-bit red, green and blue. */
fun Hsv.toRgb(): Triple<Int, Int, Int> {
    val h = ((hue % 360f) + 360f) % 360f / 60f
    val c = value * saturation
    val x = c * (1f - abs(h % 2f - 1f))
    val (r, g, b) = when (h.toInt()) {
        0 -> Triple(c, x, 0f)
        1 -> Triple(x, c, 0f)
        2 -> Triple(0f, c, x)
        3 -> Triple(0f, x, c)
        4 -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    val m = value - c
    fun channel(v: Float) = ((v + m) * 255f + 0.5f).toInt().coerceIn(0, 255)
    return Triple(channel(r), channel(g), channel(b))
}

/** [argb]'s 8-bit red, green and blue. */
fun rgbOf(argb: Long): Triple<Int, Int, Int> =
    Triple(((argb shr 16) and 0xFF).toInt(), ((argb shr 8) and 0xFF).toInt(), (argb and 0xFF).toInt())
