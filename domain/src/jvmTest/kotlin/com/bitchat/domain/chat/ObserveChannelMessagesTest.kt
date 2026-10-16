package com.bitchat.domain.chat

import app.cash.turbine.test
import com.bitchat.domain.base.defaultContextFacade
import com.bitchat.domain.chat.eventbus.ChatEventBus
import com.bitchat.domain.chat.eventbus.InMemoryChatEventBus
import com.bitchat.domain.chat.model.BitchatMessage
import com.bitchat.domain.chat.model.ChatEvent
import com.bitchat.domain.chat.repository.ChatRepository
import com.bitchat.domain.location.model.Channel
import com.bitchat.domain.location.model.GeohashChannelLevel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class ObserveChannelMessagesTest {
    private val chatRepository = mockk<ChatRepository> {
        every { observeMiningStatus() } returns MutableStateFlow(null)
    }
    private val chatEventBus = InMemoryChatEventBus(defaultContextFacade)
    private val useCase = ObserveChannelMessages(chatRepository, chatEventBus)

    private fun message(id: String) = BitchatMessage(id = id, sender = "alice", content = id, timestamp = Instant.fromEpochSeconds(1))
    private val first = listOf(message("a"))
    private val second = listOf(message("a"), message("b"))

    @Test
    fun `the mesh channel starts with its messages`() = runTest {
        coEvery { chatRepository.getMeshMessages() } returns first

        useCase(Channel.Mesh).test {
            assertEquals(first, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `mesh - a matching event published during the first read is read after it`() = runTest {
        val firstRead = CompletableDeferred<Unit>()
        coEvery { chatRepository.getMeshMessages() } coAnswers { firstRead.await(); first } andThen second

        useCase(Channel.Mesh).test {
            chatEventBus.update(ChatEvent.MeshMessagesUpdated)
            chatEventBus.update(ChatEvent.ChannelJoined)
            firstRead.complete(Unit)

            assertEquals(first, awaitItem())
            assertEquals(second, awaitItem())
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 2) { chatRepository.getMeshMessages() }
    }

    @Test
    fun `private chat - a matching event published during the first read is read after it`() = runTest {
        val firstRead = CompletableDeferred<Unit>()
        coEvery { chatRepository.getPrivateChats() } coAnswers { firstRead.await(); mapOf("aa" to first) } andThen mapOf("aa" to second)

        useCase(Channel.MeshDM("aa")).test {
            chatEventBus.update(ChatEvent.PrivateChatsUpdated)
            chatEventBus.update(ChatEvent.ChannelJoined)
            firstRead.complete(Unit)

            assertEquals(first, awaitItem())
            assertEquals(second, awaitItem())
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 2) { chatRepository.getPrivateChats() }
    }

    @Test
    fun `named channel - a matching event published during the first read is read after it`() = runTest {
        val firstRead = CompletableDeferred<Unit>()
        coEvery { chatRepository.getNamedChannelMessages("#dev") } coAnswers { firstRead.await(); first } andThen second

        useCase(Channel.NamedChannel("#dev")).test {
            chatEventBus.update(ChatEvent.NamedChannelMessagesUpdated("#dev"))
            chatEventBus.update(ChatEvent.NamedChannelMessagesUpdated("#other"))
            firstRead.complete(Unit)

            assertEquals(first, awaitItem())
            assertEquals(second, awaitItem())
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 2) { chatRepository.getNamedChannelMessages("#dev") }
    }

    @Test
    fun `geohash - a matching event published during the first read is read after it`() = runTest {
        val firstRead = CompletableDeferred<Unit>()
        coEvery { chatRepository.getGeohashMessages("u4pru") } coAnswers { firstRead.await(); first } andThen second

        useCase(Channel.Location(GeohashChannelLevel.NEIGHBORHOOD, "u4pru")).test {
            chatEventBus.update(ChatEvent.GeohashMessagesUpdated("u4pru"))
            chatEventBus.update(ChatEvent.GeohashMessagesUpdated("other"))
            firstRead.complete(Unit)

            assertEquals(first, awaitItem())
            assertEquals(second, awaitItem())
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 2) { chatRepository.getGeohashMessages("u4pru") }
    }

    @Test
    fun `geohash - on a multi-threaded dispatcher the bus is subscribed before the first read`() = runTest {
        // The producer is held just before it subscribes; a read that does not wait for the subscription starts meanwhile.
        val readStarted = CountDownLatch(1)
        val bus = SubscriptionWatchingBus(beforeSubscribing = { readStarted.await(500, TimeUnit.MILLISECONDS) })
        var subscribedAtFirstRead: Boolean? = null
        coEvery { chatRepository.getGeohashMessages("u4pru") } answers {
            if (subscribedAtFirstRead == null) subscribedAtFirstRead = bus.subscribed
            readStarted.countDown()
            first
        }

        ObserveChannelMessages(chatRepository, bus)(Channel.Location(GeohashChannelLevel.NEIGHBORHOOD, "u4pru"))
            .flowOn(Dispatchers.Default)
            .test {
                assertEquals(first, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        assertEquals(true, subscribedAtFirstRead)
    }

    private class SubscriptionWatchingBus(private val beforeSubscribing: () -> Unit) : ChatEventBus {
        private val events = MutableSharedFlow<ChatEvent>(replay = 1)

        @Volatile
        var subscribed = false
            private set

        override fun events(): Flow<ChatEvent> = flow {
            beforeSubscribing()
            emitAll(events.onSubscription { subscribed = true })
        }

        override suspend fun update(event: ChatEvent) = events.emit(event)
    }
}
