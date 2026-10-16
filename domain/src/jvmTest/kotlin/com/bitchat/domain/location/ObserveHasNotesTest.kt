package com.bitchat.domain.location

import app.cash.turbine.test
import com.bitchat.domain.app.model.ActiveState
import com.bitchat.domain.app.model.UserState
import com.bitchat.domain.base.defaultContextFacade
import com.bitchat.domain.location.eventbus.InMemoryLocationEventBus
import com.bitchat.domain.location.model.Channel
import com.bitchat.domain.location.model.GeohashChannelLevel
import com.bitchat.domain.location.model.LocationEvent
import com.bitchat.domain.location.repository.LocationRepository
import com.bitchat.domain.user.repository.UserRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest

class ObserveHasNotesTest {
    private val locationRepository = mockk<LocationRepository>()
    private val locationEventBus = InMemoryLocationEventBus(defaultContextFacade)
    private val userRepository = mockk<UserRepository> {
        coEvery { getUserState() } returns UserState.Active(ActiveState.Chat(Channel.Location(GeohashChannelLevel.NEIGHBORHOOD, "u4pru")))
    }
    private val useCase = ObserveHasNotes(locationRepository, locationEventBus, userRepository)

    @Test
    fun `the start emits whether the selected geohash has notes`() = runTest {
        coEvery { locationRepository.hasNotes("u4pru") } returns true

        useCase(Unit).test {
            assertEquals(true, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a matching event published during the first read is read after it`() = runTest {
        val firstRead = CompletableDeferred<Unit>()
        coEvery { locationRepository.hasNotes("u4pru") } coAnswers { firstRead.await(); false } andThen true

        useCase(Unit).test {
            locationEventBus.update(LocationEvent.NotesChanged)
            locationEventBus.update(LocationEvent.ParticipantsChanged)
            firstRead.complete(Unit)

            assertEquals(false, awaitItem())
            assertEquals(true, awaitItem())
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 2) { locationRepository.hasNotes("u4pru") }
    }
}
