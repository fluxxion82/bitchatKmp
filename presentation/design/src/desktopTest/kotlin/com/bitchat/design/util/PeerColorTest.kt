package com.bitchat.design.util

import androidx.compose.ui.graphics.Color
import com.bitchat.domain.chat.model.BitchatMessage
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * The peer colours moved to `presentation:viewvo`, so the terminal UI can draw a person in the
 * colour this app draws them in. What this app shows must not have changed: every colour here is
 * checked against the implementation as it was before the move, kept below.
 */
class PeerColorTest {
    private val seeds = listOf(
        "alice", "bob", "carol", "", "anon1234", "Ada",
        "noise:0011223344556677", "nostr:nostr_ab12cd34ef56ab78",
        "über", "peer with spaces", "9q8yy",
    )

    @Test fun everySeedKeepsTheColourItHadBeforeTheMove() {
        for (seed in seeds) {
            for (isDark in listOf(true, false)) {
                assertEquals(previousColorForPeerSeed(seed, isDark), colorForPeerSeed(seed, isDark), "$seed dark=$isDark")
            }
        }
    }

    @Test fun aMessageIsColouredFromTheSameSeedAsBefore() {
        val messages = listOf(
            message(sender = "alice", peerID = null),
            message(sender = "bob", peerID = "0011223344556677"),
            message(sender = "carol", peerID = "nostr_ab12cd34ef56ab78"),
            message(sender = "dave", peerID = "a".repeat(64)),
            message(sender = "erin", peerID = "short"),
        )
        for (message in messages) {
            for (isDark in listOf(true, false)) {
                assertEquals(previousGetPeerColor(message, isDark), getPeerColor(message, isDark), "${message.sender} dark=$isDark")
            }
        }
    }

    private fun message(sender: String, peerID: String?) = BitchatMessage(
        id = sender, sender = sender, content = "hi", timestamp = Instant.fromEpochSeconds(0), senderPeerID = peerID,
    )

    /** `getPeerColor` as it was before the move. */
    private fun previousGetPeerColor(message: BitchatMessage, isDark: Boolean): Color {
        val seed = when {
            message.senderPeerID?.startsWith("nostr:") == true || message.senderPeerID?.startsWith("nostr_") == true ->
                "nostr:${message.senderPeerID?.lowercase()}"
            message.senderPeerID?.length == 16 -> "noise:${message.senderPeerID?.lowercase()}"
            message.senderPeerID?.length == 64 -> "noise:${message.senderPeerID?.lowercase()}"
            else -> message.sender.lowercase()
        }
        return previousColorForPeerSeed(seed, isDark)
    }

    /** `colorForPeerSeed` as it was before the move. */
    private fun previousColorForPeerSeed(seed: String, isDark: Boolean): Color {
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
        return Color.hsv(
            hue = (hue * 360).toFloat(),
            saturation = saturation.toFloat(),
            value = brightness.toFloat(),
        )
    }
}
