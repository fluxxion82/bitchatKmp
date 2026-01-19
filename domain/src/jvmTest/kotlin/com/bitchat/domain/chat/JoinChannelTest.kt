package com.bitchat.domain.chat

import com.bitchat.domain.base.model.Outcome
import com.bitchat.domain.chat.eventbus.ChatEventBus
import com.bitchat.domain.chat.model.ChannelInfo
import com.bitchat.domain.chat.repository.ChatRepository
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JoinChannelTest {

    private val chatRepository = mockk<ChatRepository>(relaxed = true)
    private val chatEventBus = mockk<ChatEventBus>(relaxed = true)
    private val joinChannel = JoinChannel(chatRepository, chatEventBus)

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
}
