package com.bitchat.domain.chat

import com.bitchat.domain.chat.model.BitchatMessage
import com.bitchat.domain.location.model.Channel
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlin.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The system lines the app has shown (command feedback: "joined channel #test", "peers: ..."),
 * by the conversation each belongs to. They are kept here, for the life of the app rather than of
 * a screen, and merged into that conversation's messages when it is shown, because the messages
 * themselves come from the transports: a line appended to the list on screen would be gone with
 * the next update of that chat, or the next switch to another, and a chat opened in a new screen
 * would not have the feedback that led to it.
 *
 * Bounded twice over, since nothing but the app ending empties this on its own: at most [limit]
 * lines per conversation, and at most [conversations] conversations, the least recently written
 * to going first. Deleting a conversation's history ([clear], called by `/clear` and by leaving a
 * channel) drops its lines with it, and wiping the app's data ([reset]) drops all of them: these
 * lines name peers and channels, and none of that belongs to the identity that comes after.
 */
class ChatNotices(private val limit: Int = 100, private val conversations: Int = 32) {
    private val _notices = MutableStateFlow(Lines())
    private val resets = Mutex()

    /** Every conversation's lines, by [conversationKey]. */
    val notices: StateFlow<Lines> = _notices.asStateFlow()

    /** True while [reset] runs: no command may start against stores that are half cleared. */
    val resetting: Boolean get() = _notices.value.resetting

    /**
     * The store as it is now, to be carried by work that will file a line when it finishes. A
     * command suspends, and the user can wipe their data while it runs: its answer belongs to the
     * identity it was asked under, so it is dropped rather than shown to the next one.
     */
    fun epoch(): Epoch = Epoch(_notices.value.epoch)

    /**
     * Files [message] under [channel]. [notBefore] is the newest message that conversation held
     * when the line was made: the line is never shown above it, so feedback cannot be buried in
     * the middle of the scrollback by a message with a timestamp in the future.
     */
    suspend fun add(channel: Channel, message: BitchatMessage, notBefore: Instant? = null) {
        val started = coroutineContext[Epoch]?.value
        val key = channel.conversationKey()
        val anchor = if (notBefore != null && notBefore > message.timestamp) notBefore else message.timestamp
        _notices.update { lines ->
            // The epoch and the lines are one value, so a reset cannot slip between this check and
            // the write it guards: either the write lands in the epoch it was checked against, or
            // the compare-and-set fails and the check runs again against the new one.
            if (started != null && started != lines.epoch) return@update lines
            val kept = LinkedHashMap(lines.byConversation)
            // Removed and put back, so the conversation written to last is last in the order.
            val updated = (kept.remove(key).orEmpty() + Notice(message, anchor)).takeLast(limit)
            kept[key] = updated
            while (kept.size > conversations) kept.remove(kept.keys.first())
            lines.withConversations(kept)
        }
    }

    /** Drops [channel]'s lines, as `/clear` drops its messages. */
    fun clear(channel: Channel) {
        val key = channel.conversationKey()
        _notices.update { lines ->
            if (key in lines.byConversation) lines.withConversations(lines.byConversation - key) else lines
        }
    }

    /** [channel]'s lines, oldest first. */
    fun of(channel: Channel): List<Notice> = _notices.value.of(channel.conversationKey())

    /**
     * Empties the store around [clearData], the rest of the identity reset, as one barrier.
     *
     * Raising the epoch on the way in invalidates every command already running, so nothing it
     * learned from the old identity can be filed. [resetting] then keeps new commands from
     * starting, because the repositories are only half cleared while [clearData] runs. Raising it
     * again on the way out invalidates whatever did begin inside the wipe anyway, so only work
     * started under the identity that comes after can file a line.
     *
     * One at a time: two wipes at once would have the first to finish admit commands again while
     * the second was still clearing stores. A second one waits and then runs in full.
     */
    suspend fun reset(clearData: suspend () -> Unit) {
        resets.withLock {
            _notices.update { Lines(epoch = it.epoch + 1, resetting = true) }
            try {
                clearData()
            } finally {
                _notices.update { Lines(epoch = it.epoch + 1, resetting = false) }
            }
        }
    }

    /** What [epoch] hands out: the store's state, carried in a coroutine's context. */
    class Epoch(val value: Long) : AbstractCoroutineContextElement(Epoch) {
        companion object Key : CoroutineContext.Key<Epoch>

        override fun equals(other: Any?): Boolean = other is Epoch && other.value == value
        override fun hashCode(): Int = value.hashCode()
        override fun toString(): String = "Epoch($value)"
    }

    /**
     * Everything the store holds at one moment: the lines, the epoch they belong to, and whether
     * a reset is running. One value, so no update of it can be split by another.
     */
    class Lines internal constructor(
        internal val byConversation: Map<String, List<Notice>> = emptyMap(),
        internal val epoch: Long = 0,
        internal val resetting: Boolean = false,
    ) {
        /** The lines of the conversation with this [conversationKey], oldest first. */
        fun of(key: String): List<Notice> = byConversation[key].orEmpty()

        internal fun withConversations(byConversation: Map<String, List<Notice>>) =
            Lines(byConversation, epoch, resetting)
    }
}

/** A system line and the moment it belongs after (see [ChatNotices.add]). */
data class Notice(val message: BitchatMessage, val anchor: Instant)

/**
 * [messages] with each of [notices] placed before the first message later than it anchors, the
 * messages' own order untouched.
 */
fun mergeNotices(messages: List<BitchatMessage>, notices: List<Notice>): List<BitchatMessage> {
    if (notices.isEmpty()) return messages
    val result = ArrayList<BitchatMessage>(messages.size + notices.size)
    val sorted = notices.sortedBy { it.anchor }
    var next = 0
    for (message in messages) {
        while (next < sorted.size && sorted[next].anchor < message.timestamp) result += sorted[next++].message
        result += message
    }
    while (next < sorted.size) result += sorted[next++].message
    return result
}
