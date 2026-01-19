package com.bitchat.domain.chat

import app.cash.turbine.test
import com.bitchat.domain.base.defaultContextFacade
import com.bitchat.domain.chat.eventbus.InMemoryChatEventBus
import com.bitchat.domain.chat.model.BitchatMessage
import com.bitchat.domain.chat.model.ChatEvent
import com.bitchat.domain.chat.repository.ChatRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class ObservePrivateChatsTest {
    private val chatRepository = mockk<ChatRepository>()
    private val chatEventBus = InMemoryChatEventBus(defaultContextFacade)
    private val useCase = ObservePrivateChats(chatRepository, chatEventBus)

    @Test
    fun `emits initial private chats on subscription`() = runTest {
        val message = BitchatMessage(
            id = "1",
            sender = "alice",
            content = "hi",
            timestamp = Instant.fromEpochMilliseconds(0)
        )
        val initialChats = mapOf("nostr_abcd" to listOf(message))
        coEvery { chatRepository.getPrivateChats() } returns initialChats

        useCase(Unit).test {
            assertEquals(initialChats, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `emits updated private chats on event`() = runTest {
        val initialChats = mapOf("nostr_abcd" to emptyList<BitchatMessage>())
        val updatedChats = mapOf("nostr_efgh" to emptyList<BitchatMessage>())
        coEvery { chatRepository.getPrivateChats() } returnsMany listOf(initialChats, updatedChats)

        useCase(Unit).test {
            assertEquals(initialChats, awaitItem())

            chatEventBus.update(ChatEvent.PrivateChatsUpdated)
            assertEquals(updatedChats, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }
}
