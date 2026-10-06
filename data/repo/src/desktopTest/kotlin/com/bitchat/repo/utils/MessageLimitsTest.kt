package com.bitchat.repo.utils

import com.bitchat.domain.chat.model.BitchatMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import kotlin.time.Instant

class MessageLimitsTest {
    private val limits = MessageLimits(maxMessagesPerChat = 3, maxCharsPerChat = 10)

    @Test fun trimDropsTheOldestMessagesPastTheCountLimit() {
        val countOnly = MessageLimits(maxMessagesPerChat = 3, maxCharsPerChat = 100)
        val messages = messages("one", "two", "three", "four")

        assertEquals(listOf("one"), countOnly.trim(messages).map { it.content })
        assertEquals(listOf("two", "three", "four"), messages.map { it.content })
    }

    @Test fun trimDropsTheOldestMessagesPastTheCharacterLimit() {
        val messages = messages("1234", "5678", "90ab")

        assertEquals(listOf("1234"), limits.trim(messages).map { it.content })
        assertEquals(listOf("5678", "90ab"), messages.map { it.content })
    }

    @Test fun trimHonoursBothLimitsTogether() {
        val messages = messages("1234", "5678", "90", "abcdef")

        assertEquals(listOf("1234", "5678"), limits.trim(messages).map { it.content })
        assertEquals(listOf("90", "abcdef"), messages.map { it.content })
    }

    @Test fun trimNeverDropsTheMessageThatJustArrived() {
        // Alone past the character limit: it stays, and everything before it goes.
        val messages = messages("12", "34", "0123456789abcdef")

        assertEquals(listOf("12", "34"), limits.trim(messages).map { it.content })
        assertEquals(listOf("0123456789abcdef"), messages.map { it.content })
        assertTrue(limits.trim(messages).isEmpty())
    }

    @Test fun trimKeepsMessagesExactlyAtBothLimits() {
        val messages = messages("1234", "5678", "90")

        assertTrue(limits.trim(messages).isEmpty())
        assertEquals(listOf("1234", "5678", "90"), messages.map { it.content })
    }

    @Test fun trimmedDoesNotChangeItsArgument() {
        val input = messages("1234", "5678", "90ab")

        val result = limits.trimmed(input)

        assertNotSame(input, result)
        assertEquals(listOf("1234", "5678", "90ab"), input.map { it.content })
        assertEquals(listOf("5678", "90ab"), result.map { it.content })
    }

    @Test fun acceptsExactlySixtyThousandCharactersAndRejectsOneMore() {
        assertTrue(MessageLimits().accepts(message("x".repeat(BitchatMessage.MAX_CONTENT_CHARS))))
        assertFalse(MessageLimits().accepts(message("x".repeat(BitchatMessage.MAX_CONTENT_CHARS + 1))))
    }

    @Test fun notLaterThanCapsOnlyFutureTimestamps() {
        val now = Instant.fromEpochSeconds(100)

        assertEquals(now, limits.notLaterThan(message("future", 101), now).timestamp)
        assertEquals(now, limits.notLaterThan(message("present", 100), now).timestamp)
        assertEquals(Instant.fromEpochSeconds(99), limits.notLaterThan(message("past", 99), now).timestamp)
    }

    private fun messages(vararg content: String) = content.mapIndexed { index, value ->
        message(value, index.toLong())
    }.toMutableList()

    private fun message(content: String, timestamp: Long = 0) = BitchatMessage(
        id = "$content-$timestamp",
        sender = "sender",
        content = content,
        timestamp = Instant.fromEpochSeconds(timestamp),
    )
}
