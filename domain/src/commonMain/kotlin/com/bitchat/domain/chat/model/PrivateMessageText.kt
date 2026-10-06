package com.bitchat.domain.chat.model

/**
 * What one private text message can carry. The private message encoding shared with the upstream
 * clients gives the content a length of one byte, on the mesh and inside a Nostr DM alike, so the
 * limit is counted in bytes of UTF-8 and not in characters.
 */
object PrivateMessageText {
    const val MAX_BYTES: Int = 255

    /** Why [content] cannot be sent as a private message, or null when it can. */
    fun refusal(content: String): String? {
        val bytes = content.encodeToByteArray().size
        return if (bytes <= MAX_BYTES) null else "a private message can be at most $MAX_BYTES bytes, this one is $bytes"
    }
}
