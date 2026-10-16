package com.bitchat.domain.location

import app.cash.turbine.test
import com.bitchat.domain.base.defaultContextFacade
import com.bitchat.domain.location.eventbus.InMemoryLocationEventBus
import com.bitchat.domain.location.model.LocationEvent
import com.bitchat.domain.location.model.PermissionState
import com.bitchat.domain.location.repository.LocationRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest

class ObservePermissionStateTest {
    private val locationRepository = mockk<LocationRepository>()
    private val locationEventBus = InMemoryLocationEventBus(defaultContextFacade)
    private val useCase = ObservePermissionState(locationRepository, locationEventBus)

    @Test
    fun `the start emits what is stored`() = runTest {
        coEvery { locationRepository.getPermissionState() } returns PermissionState.AUTHORIZED

        useCase(Unit).test {
            assertEquals(PermissionState.AUTHORIZED, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a matching event published during the first read is read after it`() = runTest {
        val firstRead = CompletableDeferred<Unit>()
        coEvery { locationRepository.getPermissionState() } coAnswers { firstRead.await(); PermissionState.NOT_DETERMINED } andThen PermissionState.AUTHORIZED

        useCase(Unit).test {
            locationEventBus.update(LocationEvent.PermissionStateChanged)
            locationEventBus.update(LocationEvent.ParticipantsChanged)
            firstRead.complete(Unit)

            assertEquals(PermissionState.NOT_DETERMINED, awaitItem())
            assertEquals(PermissionState.AUTHORIZED, awaitItem())
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 2) { locationRepository.getPermissionState() }
    }
}
