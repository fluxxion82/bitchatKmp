package com.bitchat.domain.location

import com.bitchat.domain.chat.eventbus.ChatEventBus
import com.bitchat.domain.chat.model.ChatEvent
import com.bitchat.domain.chat.repository.ChatRepository
import com.bitchat.domain.location.eventbus.LocationEventBus
import com.bitchat.domain.location.model.GeoPerson
import com.bitchat.domain.location.model.LocationEvent
import com.bitchat.domain.location.repository.LocationRepository
import com.bitchat.domain.user.eventbus.UserEventBus
import com.bitchat.domain.user.model.UserEvent
import com.bitchat.domain.user.repository.UserRepository
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals

class ObserveChannelParticipantsTest {
    @Ignore("Test needs to be updated after channel refactor")
    @Test
    fun emitsPeopleAfterInitializingSelectedLocationChannel() = runTest {
        val repo: LocationRepository = mockk()
        val locationEventBus = object : LocationEventBus {
            override fun events(): Flow<com.bitchat.domain.location.model.LocationEvent> = emptyFlow()
            override suspend fun update(event: com.bitchat.domain.location.model.LocationEvent) = Unit
        }
        val chatEventBus = object : ChatEventBus {
            override fun events(): Flow<ChatEvent> = emptyFlow()
            override suspend fun update(event: ChatEvent) = Unit
        }
        val userEventBus = object : UserEventBus {
            override fun events(): Flow<UserEvent> = emptyFlow()
            override suspend fun update(event: UserEvent) = Unit
        }
        val chatRepository: ChatRepository = mockk()
        val userRepository: UserRepository = mockk()
        val observeChannelParticipants =
            ObserveChannelParticipants(repo, chatRepository, locationEventBus, chatEventBus, userRepository, userEventBus)

        val people = observeChannelParticipants(Unit).first()

        assertEquals(1, people.size)
        assertEquals("pubkey", people.first().id)
    }

    @Ignore("Test needs to be updated after channel refactor")
    @Test
    fun emitsMeshPeersAfterChatEventWithoutLocationEvent() = runTest {
        val repo: LocationRepository = mockk()
        val locationEventBus = object : LocationEventBus {
            override fun events(): Flow<LocationEvent> = emptyFlow()
            override suspend fun update(event: LocationEvent) = Unit
        }
        val chatEvents = kotlinx.coroutines.flow.MutableSharedFlow<ChatEvent>()
        val chatEventBus = object : ChatEventBus {
            override fun events(): Flow<ChatEvent> = chatEvents
            override suspend fun update(event: ChatEvent) {
                chatEvents.emit(event)
            }
        }
        val userEventBus = object : UserEventBus {
            override fun events(): Flow<UserEvent> = emptyFlow()
            override suspend fun update(event: UserEvent) = Unit
        }
        val chatRepository: ChatRepository = mockk()
        val userRepository: UserRepository = mockk()
        val observeChannelParticipants =
            ObserveChannelParticipants(repo, chatRepository, locationEventBus, chatEventBus, userRepository, userEventBus)

        val emissions = mutableListOf<List<GeoPerson>>()
        val collectJob = launch {
            observeChannelParticipants(Unit).take(2).toList(emissions)
        }

        runCurrent()
        chatEventBus.update(ChatEvent.MeshPeersUpdated)

        withTimeout(1_000) {
            collectJob.join()
        }

        assertEquals(2, emissions.size)
        assertEquals("peer-1", emissions.last().first().id)
    }
}
