package com.bitchat.repo.utils

import com.bitchat.domain.chat.model.BitchatMessage
import kotlin.time.Instant

/**
 * How much chat history is kept in memory. Nothing that sends a message is authenticated as a person:
 * a mesh peer, a LoRa node and a Nostr key can each send as much as they like, and every message used to
 * be kept for as long as the app ran.
 *
 * - One message is at most [BitchatMessage.MAX_CONTENT_CHARS] characters; a longer one is not kept at all.
 * - One chat keeps at most [maxMessagesPerChat] messages and [maxCharsPerChat] characters. Past either,
 *   the oldest go, the user's own included. "Oldest" is the front of the list, and the lists are kept
 *   in the order messages ARRIVED: the newest arrival is never the one dropped.
 * - All private chats the user has not written in share [maxStrangerMessages] messages and
 *   [maxStrangerChars] characters between them (`ChatRepo.enforceStrangerMessageBudget`): any Nostr key
 *   can open a private chat by sending one message, so the number of chats is not the user's choice.
 * - [maxTrackedReceiptIds] is the size of each set of receipt and acknowledgement ids already handled.
 *
 * Why arrival order and not the timestamps: three of the four kinds of chat are SHOWN sorted by the
 * timestamp the sender wrote, and that timestamp is whatever the sender likes. Dropping "the oldest" by
 * it would let a sender decide whose messages go: date a flood slightly ahead and every honest message
 * after it is the oldest in the list the moment it arrives. So the lists are stored as they arrived,
 * sorted only when read, and [notLaterThan] keeps a future-dated message from being shown below
 * everything that comes after it.
 *
 * A message that is dropped is only forgotten here: a received file it pointed to stays on disk.
 */
data class MessageLimits(
    val maxMessagesPerChat: Int = 1337,
    val maxCharsPerChat: Int = 1_000_000,
    val maxStrangerMessages: Int = 5_000,
    val maxStrangerChars: Int = 5_000_000,
    val maxTrackedReceiptIds: Int = 4_096,
) {
    init {
        require(maxMessagesPerChat > 0) { "maxMessagesPerChat must be positive" }
        require(maxCharsPerChat >= 0) { "maxCharsPerChat must not be negative" }
        require(maxStrangerMessages > 0) { "maxStrangerMessages must be positive" }
        require(maxStrangerChars >= 0) { "maxStrangerChars must not be negative" }
        require(maxTrackedReceiptIds > 0) { "maxTrackedReceiptIds must be positive" }
    }

    /** Whether [message] may be kept at all: its content is no longer than BitchatMessage.MAX_CONTENT_CHARS. */
    fun accepts(message: BitchatMessage): Boolean = message.content.length <= BitchatMessage.MAX_CONTENT_CHARS

    /**
     * Drops from the FRONT of [messages] until both limits hold; returns what was dropped (usually
     * nothing). The last message, the one that just arrived, always stays, even when it alone is past
     * the character limit (it is at most [BitchatMessage.MAX_CONTENT_CHARS] long).
     */
    fun trim(messages: MutableList<BitchatMessage>): List<BitchatMessage> {
        var chars = messages.sumOf { it.content.length.toLong() }
        var removeCount = 0
        while (removeCount < messages.size - 1 &&
            (messages.size - removeCount > maxMessagesPerChat || chars > maxCharsPerChat)
        ) {
            chars -= messages[removeCount].content.length
            removeCount++
        }
        if (removeCount == 0) return emptyList()
        val dropped = messages.take(removeCount)
        messages.subList(0, removeCount).clear()
        return dropped
    }

    /** The same for a list that is replaced rather than changed (the geohash StateFlows). */
    fun trimmed(messages: List<BitchatMessage>): List<BitchatMessage> = messages.toMutableList().also(::trim)

    /** [message] with a timestamp no later than [now]. */
    fun notLaterThan(message: BitchatMessage, now: Instant): BitchatMessage =
        if (message.timestamp > now) message.copy(timestamp = now) else message
}
