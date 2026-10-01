package com.bitchat.nostr

import com.bitchat.nostr.model.NostrIdentity
import com.bitchat.nostr.util.dmClient
import com.bitchat.nostr.util.giftWrap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A relay can hand this client any kind-1059 event it likes, so each envelope layer is decoded only
 * while it is within [NostrClient.MAX_ENVELOPE_CHARS].
 */
class EnvelopeBoundsTest {
    private val client = dmClient()
    private val sender = NostrIdentity.generate()
    private val recipient = NostrIdentity.generate()

    @Test
    fun `an envelope past the bound is refused rather than decrypted and parsed`() {
        // Well formed and genuinely sealed to us, so only the bound can turn it away.
        val oversized = client.giftWrap("a".repeat(100_000), sender, recipient)
        assertTrue(
            oversized.content.length > NostrClient.MAX_ENVELOPE_CHARS,
            "fixture is not oversized: ${oversized.content.length} chars",
        )

        assertNull(client.decryptPrivateMessage(oversized, recipient))
    }

    @Test
    fun `an envelope within the bound still opens`() {
        val wrap = client.giftWrap("bitchat1:within-the-bound", sender, recipient)
        assertTrue(wrap.content.length <= NostrClient.MAX_ENVELOPE_CHARS)

        assertEquals("bitchat1:within-the-bound", client.decryptPrivateMessage(wrap, recipient)?.first)
    }
}
