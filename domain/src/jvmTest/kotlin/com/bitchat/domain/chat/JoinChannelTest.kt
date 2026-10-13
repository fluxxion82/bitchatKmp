package com.bitchat.domain.chat

import com.bitchat.domain.base.model.Outcome
import com.bitchat.domain.chat.eventbus.ChatEventBus
import com.bitchat.domain.chat.model.ChannelInfo
import com.bitchat.domain.chat.model.ChatEvent
import com.bitchat.domain.chat.model.failure.ChannelFailure
import com.bitchat.domain.chat.repository.ChatRepository
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JoinChannelTest {

    private val chatRepository = mockk<ChatRepository>(relaxed = true)
    private val chatEventBus = mockk<ChatEventBus>(relaxed = true)
    private val joinChannel = JoinChannel(chatRepository, chatEventBus)

    private fun protectedChannel(commitment: String?) = ChannelInfo(
        name = "#test",
        isProtected = true,
        memberCount = 1,
        creatorNpub = "npub",
        keyCommitment = commitment,
        isOwner = false,
        nostrEventId = "event-id"
    )

    private fun available(channel: ChannelInfo) {
        coEvery { chatRepository.getJoinedChannelsList() } returns emptyList()
        coEvery { chatRepository.getAvailableChannels() } returns listOf(channel)
    }

    private fun passwordCommitsTo(password: String, commitment: String) {
        val key = password.encodeToByteArray()
        coEvery { chatRepository.deriveChannelKey("#test", password) } returns key
        coEvery { chatRepository.calculateKeyCommitment(key) } returns commitment
    }

    @Test
    fun `join uses discovered channel instead of creating a new one`() = runTest {
        val discovered = ChannelInfo(
            name = "#test",
            isProtected = false,
            memberCount = 1,
            creatorNpub = "npub",
            keyCommitment = null,
            isOwner = false,
            nostrEventId = "event-id"
        )

        coEvery { chatRepository.getJoinedChannelsList() } returns emptyList()
        coEvery { chatRepository.getAvailableChannels() } returns emptyList()
        coEvery { chatRepository.discoverNamedChannel("#test") } returns discovered
        coEvery { chatRepository.joinChannel("#test", null) } returns true
        coJustRun { chatRepository.ensureNamedChannelMetadata(discovered) }

        val result = joinChannel(JoinChannel.Params("test"))

        assertTrue(result is Outcome.Success)
        assertFalse(result.value.isNewChannel)

        coVerify(exactly = 1) { chatRepository.joinChannel("#test", null) }
        coVerify(exactly = 1) { chatRepository.ensureNamedChannelMetadata(discovered) }
        coVerify(exactly = 0) { chatRepository.createNamedChannel(any(), any()) }
    }

    @Test
    fun `a join the repository refuses fails as JoinFailed`() = runTest {
        val existing = ChannelInfo(
            name = "#test",
            isProtected = false,
            memberCount = 1,
            creatorNpub = "npub",
            keyCommitment = null,
            isOwner = false,
            nostrEventId = "event-id"
        )
        coEvery { chatRepository.getJoinedChannelsList() } returns emptyList()
        coEvery { chatRepository.getAvailableChannels() } returns listOf(existing)
        coEvery { chatRepository.joinChannel("#test", null) } returns false

        assertEquals(Outcome.Error(ChannelFailure.JoinFailed), joinChannel(JoinChannel.Params("test")))
    }

    @Test
    fun `a create that throws carries the reason it was given, or none`() = runTest {
        coEvery { chatRepository.getJoinedChannelsList() } returns emptyList()
        coEvery { chatRepository.getAvailableChannels() } returns emptyList()
        coEvery { chatRepository.discoverNamedChannel("#test") } returns null

        coEvery { chatRepository.createNamedChannel("#test", null) } throws IllegalStateException("relay refused")
        assertEquals(Outcome.Error(ChannelFailure.CreationFailed("relay refused")), joinChannel(JoinChannel.Params("test")))

        coEvery { chatRepository.createNamedChannel("#test", null) } throws IllegalStateException()
        assertEquals(Outcome.Error(ChannelFailure.CreationFailed(null)), joinChannel(JoinChannel.Params("test")))
    }

    @Test
    fun `a protected channel without a password fails as WrongPassword`() = runTest {
        available(protectedChannel("commitment"))

        assertEquals(Outcome.Error(ChannelFailure.WrongPassword), joinChannel(JoinChannel.Params("test")))

        coVerify(exactly = 0) { chatRepository.deriveChannelKey(any(), any()) }
        coVerify(exactly = 0) { chatRepository.setChannelPassword(any(), any()) }
        coVerify(exactly = 0) { chatRepository.joinChannel(any(), any()) }
        coVerify(exactly = 0) { chatEventBus.update(any()) }
    }

    @Test
    fun `a password whose commitment differs from the advertised one fails as WrongPassword`() = runTest {
        available(protectedChannel("commitment"))
        passwordCommitsTo("guess", "other")

        assertEquals(Outcome.Error(ChannelFailure.WrongPassword), joinChannel(JoinChannel.Params("test", "guess")))

        coVerify(exactly = 0) { chatRepository.setChannelPassword(any(), any()) }
        coVerify(exactly = 0) { chatRepository.joinChannel(any(), any()) }
        coVerify(exactly = 0) { chatEventBus.update(any()) }
    }

    // Records what happens today, not a rule: with nothing advertised there is nothing to check a password against.
    @Test
    fun `a password is not verified today when the channel advertises no commitment`() = runTest {
        available(protectedChannel(null))
        passwordCommitsTo("anything", "unchecked")
        coEvery { chatRepository.joinChannel("#test", "anything") } returns true

        val result = joinChannel(JoinChannel.Params("test", "anything"))

        assertTrue(result is Outcome.Success)
        assertFalse(result.value.isNewChannel)
        coVerifyOrder {
            chatRepository.setChannelPassword("#test", "anything")
            chatRepository.joinChannel("#test", "anything")
            chatEventBus.update(ChatEvent.ChannelJoined)
        }
        coVerifyOrder {
            chatRepository.joinChannel("#test", "anything")
            chatEventBus.update(ChatEvent.ChannelListUpdated)
        }
    }

    @Test
    fun `the right password is written and the channel joined with it`() = runTest {
        available(protectedChannel("commitment"))
        passwordCommitsTo("secret", "commitment")
        coEvery { chatRepository.joinChannel("#test", "secret") } returns true

        val result = joinChannel(JoinChannel.Params("test", "secret"))

        assertTrue(result is Outcome.Success)
        assertEquals("#test", result.value.channelInfo.name)
        coVerifyOrder {
            chatRepository.setChannelPassword("#test", "secret")
            chatRepository.joinChannel("#test", "secret")
        }
    }

    @Test
    fun `a cancelled create is not reported as a failed one`() = runTest {
        coEvery { chatRepository.getJoinedChannelsList() } returns emptyList()
        coEvery { chatRepository.getAvailableChannels() } returns emptyList()
        coEvery { chatRepository.discoverNamedChannel("#test") } returns null
        coEvery { chatRepository.createNamedChannel("#test", null) } throws CancellationException("left the screen")

        assertFailsWith<CancellationException> { joinChannel(JoinChannel.Params("test")) }
    }
}
