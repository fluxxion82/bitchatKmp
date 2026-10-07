package com.bitchat.nostr

import com.bitchat.api.dto.mapper.toBitchatPacket
import com.bitchat.noise.model.NoisePayload
import com.bitchat.noise.model.PrivateMessagePacket
import com.bitchat.nostr.model.NostrIdentity
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * A long private text is handed over as several messages in a row, and each send runs on its own
 * coroutine. The other side sorts by the time inside each message, so that time has to say the
 * order they were handed over in, whichever send runs first.
 */
class NostrTransportPrivateMessageOrderTest {
    private val me = NostrIdentity(privateKeyHex = "a1".repeat(32), publicKeyHex = "b2".repeat(32), npub = "npub1me", createdAt = 0)
    private val datedAt = ConcurrentHashMap<String, Int>()
    private val client = mockk<NostrClient>(relaxed = true).also { client ->
        every { client.getCurrentNostrIdentity() } returns me
        every { client.deriveIdentity(any()) } returns me
        every { client.createPrivateMessage(any(), any(), any(), any()) } answers {
            datedAt[firstArg()] = arg(3)
            emptyList()
        }
    }
    private val transport = NostrTransport("1112131415161718", client, mockk(relaxed = true))

    @Test
    fun directMessagesHandedOverInARowAreDatedInThatOrder() {
        val friend = Bech32.encode("npub", ByteArray(32) { 7 })
        val pieces = List(40) { "piece $it" }

        pieces.forEachIndexed { index, piece -> transport.sendPrivateMessage(piece, friend, "0102030405060708", "id-$index") }
        verify(timeout = 10_000, exactly = pieces.size) { client.createPrivateMessage(any(), any(), any(), any()) }

        assertStrictlyLaterEachTime(pieces.map(::dateOf))
    }

    @Test
    fun geohashMessagesHandedOverInARowAreDatedInThatOrder() {
        val pieces = List(40) { "piece $it" }

        pieces.forEachIndexed { index, piece -> transport.sendPrivateMessageGeohash(piece, "d4".repeat(32), "id-$index", "9q8yy") }
        verify(timeout = 10_000, exactly = pieces.size) { client.createPrivateMessage(any(), any(), any(), any()) }

        assertStrictlyLaterEachTime(pieces.map(::dateOf))
    }

    private fun assertStrictlyLaterEachTime(dates: List<Int>) {
        assertEquals(dates.indices.map { dates.first() + it }, dates)
    }

    /** The time the transport gave the message whose text is [piece], read back from what it embedded. */
    private fun dateOf(piece: String): Int = datedAt.entries.single { (content, _) -> embeddedText(content) == piece }.value

    private fun embeddedText(content: String): String {
        val encoded = content.removePrefix("bitchat1:").replace('-', '+').replace('_', '/')
        val packet = assertNotNull(Base64.getDecoder().decode(encoded + "=".repeat((4 - encoded.length % 4) % 4)).toBitchatPacket())
        val payload = assertNotNull(NoisePayload.decode(packet.payload))
        return assertNotNull(PrivateMessagePacket.decode(payload.data)).content
    }
}
