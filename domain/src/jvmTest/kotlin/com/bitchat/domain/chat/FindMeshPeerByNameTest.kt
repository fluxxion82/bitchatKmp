package com.bitchat.domain.chat

import com.bitchat.domain.chat.repository.ChatRepository
import com.bitchat.domain.location.model.GeoPerson
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class FindMeshPeerByNameTest {
    private var peers = emptyList<GeoPerson>()
    private var chatNames = linkedMapOf<String, String?>()
    private val find = FindMeshPeerByName(
        mockk<ChatRepository> {
            coEvery { getMeshPeers() } answers { peers }
            coEvery { getPrivateChatNames() } answers { chatNames }
        },
    )

    private fun peer(id: String, announces: String) = GeoPerson(id, announces, Instant.fromEpochSeconds(0))

    @Test fun `a chat's own name means its peer whatever another peer announces under an id that starts alike`() = runTest {
        // A's chat is called alice#1a2b and A announces "bob" now. M made an id with the same start and
        // announces "alice": the name the user knows A's chat by must not open a chat with M.
        peers = listOf(peer(M, "alice"), peer(A, "bob"))
        chatNames = linkedMapOf(A to "alice#1a2b")

        assertEquals(A, find("alice#1a2b")?.id)
        assertEquals(A, find("ALICE#1A2B")?.id)
    }

    @Test fun `a chat's own name finds nobody while its peer is not connected`() = runTest {
        peers = listOf(peer(M, "alice"))
        chatNames = linkedMapOf(A to "alice#1a2b")

        assertNull(find("alice#1a2b"))
    }

    @Test fun `of two chats with one name the one opened first is meant`() = runTest {
        peers = listOf(peer(M, "alice"), peer(A, "alice"))
        chatNames = linkedMapOf(A to "alice#1a2b", M to "alice#1a2b")

        assertEquals(A, find("alice#1a2b")?.id)
    }

    @Test fun `a name that is no chat's own means the peer announcing exactly that`() = runTest {
        peers = listOf(peer(B, "bob"), peer(A, "alice"))
        chatNames = linkedMapOf(A to "alice#1a2b")

        assertEquals(B, find("bob")?.id)
        assertEquals(B, find("BOB")?.id)
        // The bare name a chat was opened under still finds the peer announcing it, as it always did.
        assertEquals(A, find("alice")?.id)
        assertNull(find("carol"))
    }

    @Test fun `an announced name with the suffix of its peer's id means that peer when it has no chat yet`() = runTest {
        peers = listOf(peer(B, "bob"))

        assertEquals(B, find("bob#2b3c")?.id)
        assertNull(find("bob#ffff"))
        assertNull(find("bo#2b3c"))
    }

    @Test fun `a peer with a chat under another name is not found by its announced name with a suffix`() = runTest {
        // A is listed as alice#1a2b (now: bob). "bob#1a2b" is nobody's name: neither the chat's nor announced.
        peers = listOf(peer(A, "bob"))
        chatNames = linkedMapOf(A to "alice#1a2b")

        assertNull(find("bob#1a2b"))
        assertEquals(A, find("bob")?.id)
    }

    @Test fun `a peer that announced no name is found by no name made from its id or the placeholder's suffix`() = runTest {
        // A announced nothing; B announces the start of A's id as its name. That name is B's.
        peers = listOf(peer(A, "Unknown"), peer(B, A.take(12)))

        assertEquals(B, find(A.take(12))?.id)
        assertNull(find("Unknown#1a2b"))
        // The placeholder itself still finds the first peer listed under it, as it always did.
        assertEquals(A, find("Unknown")?.id)
    }

    @Test fun `a chat without a name of its own does not stand in the way`() = runTest {
        peers = listOf(peer(A, "alice"))
        chatNames = linkedMapOf(A to null)

        assertEquals(A, find("alice#1a2b")?.id)
    }

    @Test fun `the name of a conversation that is not a mesh chat is not looked at`() = runTest {
        peers = listOf(peer(A, "alice"))
        chatNames = linkedMapOf("nostr_1234567890abcdef" to "alice")

        assertEquals(A, find("alice")?.id)
    }

    private companion object {
        const val A = "1a2b000000000001"
        const val M = "1a2b000000000002"
        const val B = "2b3c000000000003"
    }
}
