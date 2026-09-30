package com.bitchat.tui

import com.bitchat.domain.chat.model.BitchatMessage
import com.bitchat.domain.chat.model.BitchatMessageType

/**
 * Byte sizes of the image and voice messages in a conversation, whose content is a local path.
 * Each file is looked up once through [size] (null: unknown, and not asked again), and entries for
 * messages no longer in the conversation are dropped on the next [of], so the cache never outgrows
 * the conversation it serves. Use one per conversation shown.
 */
class MediaSizes(private val size: (path: String) -> Long?) {
    private val known = HashMap<String, Long?>()

    /** The sizes by message ID for [messages]; files not looked up yet are looked up now. */
    fun of(messages: List<BitchatMessage>): Map<String, Long> {
        val result = HashMap<String, Long>()
        val present = HashSet<String>()
        for (message in messages) {
            if (message.type != BitchatMessageType.Image && message.type != BitchatMessageType.Audio) continue
            present += message.id
            val bytes = if (known.containsKey(message.id)) known[message.id] else size(message.content).also { known[message.id] = it }
            if (bytes != null) result[message.id] = bytes
        }
        known.keys.retainAll(present)
        return result
    }

    /** How many files are remembered; for tests. */
    internal val cached: Int get() = known.size
}
