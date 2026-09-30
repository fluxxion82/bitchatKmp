package com.bitchat.tui

import com.bitchat.domain.chat.model.BitchatMessage
import com.bitchat.domain.chat.model.BitchatMessageType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class MediaSizesTest {
    private val lookups = ArrayList<String>()
    private val sizes = MediaSizes { path -> lookups += path; if (path.endsWith(".gone")) null else path.length.toLong() }

    private fun message(id: String, type: BitchatMessageType, content: String) =
        BitchatMessage(id = id, sender = "bob", content = content, type = type, timestamp = Instant.fromEpochSeconds(0))

    private val photo = message("1", BitchatMessageType.Image, "/tmp/photo.jpg")
    private val voice = message("2", BitchatMessageType.Audio, "/tmp/voice.m4a")
    private val text = message("3", BitchatMessageType.Message, "/not/a/file")
    private val lost = message("4", BitchatMessageType.Image, "/tmp/x.gone")

    @Test fun eachFileIsLookedUpOnce() {
        assertEquals(mapOf("1" to 14L, "2" to 14L), sizes.of(listOf(photo, voice, text, lost)))
        assertEquals(mapOf("1" to 14L, "2" to 14L), sizes.of(listOf(photo, voice, text, lost)))
        assertEquals(listOf("/tmp/photo.jpg", "/tmp/voice.m4a", "/tmp/x.gone"), lookups)
    }

    @Test fun messagesThatLeaveTheConversationLeaveTheCache() {
        sizes.of(listOf(photo, voice, lost))
        assertEquals(3, sizes.cached)
        assertEquals(mapOf("2" to 14L), sizes.of(listOf(voice)))
        assertEquals(1, sizes.cached)
        assertEquals(emptyMap(), sizes.of(emptyList()))
        assertEquals(0, sizes.cached)
    }
}
