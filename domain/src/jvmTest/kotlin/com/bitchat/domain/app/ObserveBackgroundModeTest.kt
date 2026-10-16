package com.bitchat.domain.app

import app.cash.turbine.test
import com.bitchat.domain.app.eventbus.InMemoryAppEventBus
import com.bitchat.domain.app.model.AppEvent
import com.bitchat.domain.app.model.BackgroundMode
import com.bitchat.domain.app.repository.AppRepository
import com.bitchat.domain.base.defaultContextFacade
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest

class ObserveBackgroundModeTest {
    private val appRepository = mockk<AppRepository>()
    private val appEventBus = InMemoryAppEventBus(defaultContextFacade)
    private val useCase = ObserveBackgroundMode(appRepository, appEventBus)

    @Test
    fun `the start emits what is stored`() = runTest {
        coEvery { appRepository.getBackgroundMode() } returns BackgroundMode.ON

        useCase(Unit).test {
            assertEquals(BackgroundMode.ON, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a matching event published during the first read is read after it`() = runTest {
        val firstRead = CompletableDeferred<Unit>()
        coEvery { appRepository.getBackgroundMode() } coAnswers { firstRead.await(); BackgroundMode.OFF } andThen BackgroundMode.ON

        useCase(Unit).test {
            appEventBus.update(AppEvent.BackgroundModeChanged)
            appEventBus.update(AppEvent.ThemeUpdated)
            firstRead.complete(Unit)

            assertEquals(BackgroundMode.OFF, awaitItem())
            assertEquals(BackgroundMode.ON, awaitItem())
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 2) { appRepository.getBackgroundMode() }
    }
}
