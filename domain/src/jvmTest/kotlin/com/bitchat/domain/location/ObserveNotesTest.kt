package com.bitchat.domain.location

import app.cash.turbine.test
import com.bitchat.domain.base.defaultContextFacade
import com.bitchat.domain.location.eventbus.InMemoryLocationEventBus
import com.bitchat.domain.location.model.LocationEvent
import com.bitchat.domain.location.model.Note
import com.bitchat.domain.location.repository.LocationRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest

class ObserveNotesTest {
    private val locationRepository = mockk<LocationRepository>()
    private val locationEventBus = InMemoryLocationEventBus(defaultContextFacade)
    private val useCase = ObserveNotes(locationRepository, locationEventBus)

    private fun note(id: String) = Note(id = id, pubkey = "pk", content = id, createdAt = 1, nickname = null)

    @Test
    fun `the start emits what is stored`() = runTest {
        coEvery { locationRepository.getNotes("u4pru") } returns listOf(note("a"))

        useCase("u4pru").test {
            assertEquals(listOf(note("a")), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a matching event published during the first read is read after it`() = runTest {
        val firstRead = CompletableDeferred<Unit>()
        coEvery { locationRepository.getNotes("u4pru") } coAnswers { firstRead.await(); listOf(note("a")) } andThen listOf(note("a"), note("b"))

        useCase("u4pru").test {
            locationEventBus.update(LocationEvent.NotesChanged)
            locationEventBus.update(LocationEvent.ParticipantsChanged)
            firstRead.complete(Unit)

            assertEquals(listOf(note("a")), awaitItem())
            assertEquals(listOf(note("a"), note("b")), awaitItem())
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 2) { locationRepository.getNotes("u4pru") }
    }
}
