package com.bitchat.domain.location

import app.cash.turbine.test
import com.bitchat.domain.base.defaultContextFacade
import com.bitchat.domain.location.eventbus.InMemoryLocationEventBus
import com.bitchat.domain.location.model.LocationEvent
import com.bitchat.domain.location.repository.LocationRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest

class ObserveLocationServicesEnabledTest {
    private val locationRepository = mockk<LocationRepository>()
    private val locationEventBus = InMemoryLocationEventBus(defaultContextFacade)
    private val useCase = ObserveLocationServicesEnabled(locationRepository, locationEventBus)

    @Test
    fun `the start emits what is stored`() = runTest {
        coEvery { locationRepository.isLocationServicesEnabled() } returns true

        useCase(Unit).test {
            assertEquals(true, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a matching event published during the first read is read after it`() = runTest {
        val firstRead = CompletableDeferred<Unit>()
        coEvery { locationRepository.isLocationServicesEnabled() } coAnswers { firstRead.await(); false } andThen true

        useCase(Unit).test {
            locationEventBus.update(LocationEvent.LocationServicesChanged)
            locationEventBus.update(LocationEvent.ParticipantsChanged)
            firstRead.complete(Unit)

            assertEquals(false, awaitItem())
            assertEquals(true, awaitItem())
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 2) { locationRepository.isLocationServicesEnabled() }
    }
}
