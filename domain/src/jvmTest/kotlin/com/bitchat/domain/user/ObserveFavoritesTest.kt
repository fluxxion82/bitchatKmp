package com.bitchat.domain.user

import app.cash.turbine.test
import com.bitchat.domain.base.defaultContextFacade
import com.bitchat.domain.user.eventbus.InMemoryUserEventBus
import com.bitchat.domain.user.model.FavoriteRelationship
import com.bitchat.domain.user.model.UserEvent
import com.bitchat.domain.user.repository.UserRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ObserveFavoritesTest {
    private val userRepository = mockk<UserRepository>()
    private val userEventBus = InMemoryUserEventBus(defaultContextFacade)
    private val useCase = ObserveFavorites(userRepository, userEventBus)

    private fun favorites(vararg keys: String) = keys.associateWith { key ->
        FavoriteRelationship(
            peerNoisePublicKeyHex = key,
            peerNostrPublicKey = null,
            peerNickname = key,
            isFavorite = true,
            theyFavoritedUs = false,
            favoritedAt = 1L,
            lastUpdated = 1L,
        )
    }

    @Test
    fun `the start emits the saved favourites`() = runTest {
        coEvery { userRepository.getAllFavorites() } returns favorites("aa")

        useCase(Unit).test {
            assertEquals(favorites("aa"), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `FavoriteStatusChanged reads the favourites again`() = runTest {
        coEvery { userRepository.getAllFavorites() } returnsMany listOf(favorites("aa"), favorites("aa", "bb"))

        useCase(Unit).test {
            assertEquals(favorites("aa"), awaitItem())

            userEventBus.update(UserEvent.FavoriteStatusChanged("bb"))
            assertEquals(favorites("aa", "bb"), awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 2) { userRepository.getAllFavorites() }
    }

    @Test
    fun `FavoritesCleared reads the favourites again`() = runTest {
        coEvery { userRepository.getAllFavorites() } returnsMany listOf(favorites("aa"), emptyMap())

        useCase(Unit).test {
            assertEquals(favorites("aa"), awaitItem())

            userEventBus.update(UserEvent.FavoritesCleared)
            assertEquals(emptyMap(), awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 2) { userRepository.getAllFavorites() }
    }

    @Test
    fun `an unrelated event after the start does not read the favourites again`() = runTest {
        coEvery { userRepository.getAllFavorites() } returns favorites("aa")

        useCase(Unit).test {
            assertEquals(favorites("aa"), awaitItem())

            userEventBus.update(UserEvent.StateChanged)
            expectNoEvents()

            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 1) { userRepository.getAllFavorites() }
    }

    @Test
    fun `an unrelated event in the replay slot does not read the favourites again at the start`() = runTest {
        coEvery { userRepository.getAllFavorites() } returns favorites("aa")
        userEventBus.update(UserEvent.StateChanged)

        useCase(Unit).test {
            assertEquals(favorites("aa"), awaitItem())
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 1) { userRepository.getAllFavorites() }
    }

    @Test
    fun `two matching events give two reads even when the favourites are the same`() = runTest {
        coEvery { userRepository.getAllFavorites() } returns favorites("aa")

        useCase(Unit).test {
            assertEquals(favorites("aa"), awaitItem())

            userEventBus.update(UserEvent.FavoriteStatusChanged("aa"))
            assertEquals(favorites("aa"), awaitItem())
            userEventBus.update(UserEvent.FavoriteStatusChanged("aa"))
            assertEquals(favorites("aa"), awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 3) { userRepository.getAllFavorites() }
    }

    @Test
    fun `today a FavoriteStatusChanged in the replay slot reads the favourites a second time at the start`() = runTest {
        coEvery { userRepository.getAllFavorites() } returns favorites("aa")
        userEventBus.update(UserEvent.FavoriteStatusChanged("aa"))

        useCase(Unit).test {
            assertEquals(favorites("aa"), awaitItem())
            assertEquals(favorites("aa"), awaitItem())
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 2) { userRepository.getAllFavorites() }
    }

    @Test
    fun `a matching event published during the first read is read after it`() = runTest {
        val firstRead = CompletableDeferred<Unit>()
        coEvery { userRepository.getAllFavorites() } coAnswers { firstRead.await(); favorites("aa") } andThen favorites("aa", "bb")

        useCase(Unit).test {
            userEventBus.update(UserEvent.FavoriteStatusChanged("bb"))
            userEventBus.update(UserEvent.StateChanged)
            firstRead.complete(Unit)

            assertEquals(favorites("aa"), awaitItem())
            assertEquals(favorites("aa", "bb"), awaitItem())
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 2) { userRepository.getAllFavorites() }
    }
}
