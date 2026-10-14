package com.bitchat.domain.user

import app.cash.turbine.test
import com.bitchat.domain.app.model.ActiveState
import com.bitchat.domain.app.model.UserState
import com.bitchat.domain.app.repository.AppRepository
import com.bitchat.domain.base.defaultContextFacade
import com.bitchat.domain.connectivity.repository.ConnectivityRepository
import com.bitchat.domain.user.eventbus.InMemoryUserEventBus
import com.bitchat.domain.user.model.AppUser
import com.bitchat.domain.user.model.UserEvent
import com.bitchat.domain.user.repository.UserRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ObserveUserStateTest {
    private val userRepository = mockk<UserRepository>()
    private val appRepository = mockk<AppRepository> {
        coEvery { hasRequiredPermissions() } returns true
        coEvery { isBatteryOptimizationSkipped() } returns true
    }
    private val connectivityRepository = mockk<ConnectivityRepository> {
        coEvery { isBluetoothEnabled() } returns true
        coEvery { isLocationServicesEnabled() } returns true
    }
    private val userEventBus = InMemoryUserEventBus(defaultContextFacade)
    private val useCase = ObserveUserState(
        GetUserState(userRepository, appRepository, connectivityRepository),
        userEventBus,
    )

    private val settings = UserState.Active(ActiveState.Settings)
    private val locations = UserState.Active(ActiveState.Locations)

    @Test
    fun `the start emits the saved state`() = runTest {
        coEvery { userRepository.getUserState() } returns settings

        useCase(Unit).test {
            assertEquals(settings, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a blocking input comes through as GetUserState decides it`() = runTest {
        coEvery { connectivityRepository.isBluetoothEnabled() } returns false

        useCase(Unit).test {
            assertEquals(UserState.BluetoothDisabled, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 0) { userRepository.getUserState() }
    }

    @Test
    fun `StateChanged reads the state again`() = runTest {
        coEvery { userRepository.getUserState() } returnsMany listOf(settings, locations)

        useCase(Unit).test {
            assertEquals(settings, awaitItem())

            userEventBus.update(UserEvent.StateChanged)
            assertEquals(locations, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 2) { userRepository.getUserState() }
    }

    @Test
    fun `LoginChanged reads the state again`() = runTest {
        coEvery { userRepository.getUserState() } returnsMany listOf(settings, locations)

        useCase(Unit).test {
            assertEquals(settings, awaitItem())

            userEventBus.update(UserEvent.LoginChanged(AppUser.ActiveAnonymous("Alice")))
            assertEquals(locations, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 2) { userRepository.getUserState() }
    }

    @Test
    fun `an unrelated event after the start does not read the state again`() = runTest {
        coEvery { userRepository.getUserState() } returns settings

        useCase(Unit).test {
            assertEquals(settings, awaitItem())

            userEventBus.update(UserEvent.FavoriteStatusChanged("peer"))
            expectNoEvents()

            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 1) { userRepository.getUserState() }
    }

    @Test
    fun `an unrelated event in the replay slot does not read the state again at the start`() = runTest {
        coEvery { userRepository.getUserState() } returns settings
        userEventBus.update(UserEvent.NicknameUpdated)

        useCase(Unit).test {
            assertEquals(settings, awaitItem())
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 1) { userRepository.getUserState() }
    }

    @Test
    fun `two matching events give two reads even when the state is the same`() = runTest {
        coEvery { userRepository.getUserState() } returns settings

        useCase(Unit).test {
            assertEquals(settings, awaitItem())

            userEventBus.update(UserEvent.StateChanged)
            assertEquals(settings, awaitItem())
            userEventBus.update(UserEvent.StateChanged)
            assertEquals(settings, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 3) { userRepository.getUserState() }
    }

    @Test
    fun `today a StateChanged in the replay slot reads the state a second time at the start`() = runTest {
        coEvery { userRepository.getUserState() } returns settings
        userEventBus.update(UserEvent.StateChanged)

        useCase(Unit).test {
            assertEquals(settings, awaitItem())
            assertEquals(settings, awaitItem())
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 2) { userRepository.getUserState() }
    }

    @Test
    fun `a matching event published during the first read is read after it`() = runTest {
        val firstRead = CompletableDeferred<Unit>()
        coEvery { userRepository.getUserState() } coAnswers { firstRead.await(); settings } andThen locations

        useCase(Unit).test {
            userEventBus.update(UserEvent.StateChanged)
            userEventBus.update(UserEvent.NicknameUpdated)
            firstRead.complete(Unit)

            assertEquals(settings, awaitItem())
            assertEquals(locations, awaitItem())
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 2) { userRepository.getUserState() }
    }
}
