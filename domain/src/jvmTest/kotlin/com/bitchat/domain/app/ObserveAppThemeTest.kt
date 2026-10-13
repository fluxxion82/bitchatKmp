package com.bitchat.domain.app

import app.cash.turbine.test
import com.bitchat.domain.app.eventbus.InMemoryAppEventBus
import com.bitchat.domain.app.model.AppEvent
import com.bitchat.domain.app.model.AppTheme
import com.bitchat.domain.app.repository.AppRepository
import com.bitchat.domain.base.defaultContextFacade
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ObserveAppThemeTest {
    private val appRepository = mockk<AppRepository>()
    private val appEventBus = InMemoryAppEventBus(defaultContextFacade)
    private val useCase = ObserveAppTheme(appRepository, appEventBus)

    @Test
    fun `a background mode change in the replay slot does not read the theme again at the start`() = runTest {
        coEvery { appRepository.getAppTheme() } returns AppTheme.DARK
        appEventBus.update(AppEvent.BackgroundModeChanged)

        useCase(Unit).test {
            assertEquals(AppTheme.DARK, awaitItem())
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 1) { appRepository.getAppTheme() }
    }

    @Test
    fun `a theme update reads the theme again`() = runTest {
        coEvery { appRepository.getAppTheme() } returnsMany listOf(AppTheme.SYSTEM, AppTheme.DARK)

        useCase(Unit).test {
            assertEquals(AppTheme.SYSTEM, awaitItem())

            appEventBus.update(AppEvent.ThemeUpdated)
            assertEquals(AppTheme.DARK, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a later background mode change does not read the theme again`() = runTest {
        coEvery { appRepository.getAppTheme() } returns AppTheme.LIGHT

        useCase(Unit).test {
            assertEquals(AppTheme.LIGHT, awaitItem())

            appEventBus.update(AppEvent.BackgroundModeChanged)
            expectNoEvents()

            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 1) { appRepository.getAppTheme() }
    }
}
