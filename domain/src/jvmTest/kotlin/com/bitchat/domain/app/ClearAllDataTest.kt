package com.bitchat.domain.app

import app.cash.turbine.test
import com.bitchat.domain.app.eventbus.AppEventBus
import com.bitchat.domain.app.eventbus.InMemoryAppEventBus
import com.bitchat.domain.app.model.AppTheme
import com.bitchat.domain.app.repository.AppRepository
import com.bitchat.domain.base.defaultContextFacade
import com.bitchat.domain.chat.ChatNotices
import com.bitchat.domain.chat.model.BitchatMessage
import com.bitchat.domain.chat.model.BitchatMessageType
import com.bitchat.domain.location.model.Channel
import com.bitchat.domain.location.repository.LocationRepository
import com.bitchat.domain.user.ObserveFavorites
import com.bitchat.domain.user.ObserveUserNickname
import com.bitchat.domain.user.eventbus.InMemoryUserEventBus
import com.bitchat.domain.user.eventbus.UserEventBus
import com.bitchat.domain.user.model.AppUser
import com.bitchat.domain.user.model.FavoriteRelationship
import com.bitchat.domain.user.repository.UserRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

class ClearAllDataTest {
    private val notices = ChatNotices()

    private fun line(text: String) = BitchatMessage(
        id = text, sender = "system", content = text, type = BitchatMessageType.System,
        timestamp = Instant.fromEpochSeconds(1),
    )

    /** [whileClearing] runs in the middle of the wipe, after the first store has been cleared. */
    private fun wipe(
        appRepository: AppRepository = mockk(relaxed = true),
        appEventBus: AppEventBus = mockk(relaxed = true),
        userRepository: UserRepository = mockk(relaxed = true),
        userEventBus: UserEventBus = mockk(relaxed = true),
        afterTheAppStore: suspend () -> Unit = {},
        whileClearing: suspend () -> Unit = {},
    ) = ClearAllData(
        chatRepository = mockk(relaxed = true),
        chatNotices = notices,
        userRepository = userRepository.also { coEvery { it.clearData() } coAnswers { whileClearing() } },
        appRepository = appRepository,
        locationRepository = mockk<LocationRepository>(relaxed = true) { coEvery { clearData() } coAnswers { afterTheAppStore() } },
        blockListRepository = mockk(relaxed = true),
        nostrRepository = mockk(relaxed = true),
        torRepository = mockk(relaxed = true),
        userEventBus = userEventBus,
        appEventBus = appEventBus,
    )

    private var userStoreCleared = false

    // A new nickname and no favourites, once the wipe has cleared the user store and not before.
    private fun userStore() = mockk<UserRepository>(relaxed = true) {
        coEvery { getAppUser() } answers { AppUser.ActiveAnonymous(if (userStoreCleared) "anon1234" else "alice") }
        coEvery { getAllFavorites() } answers { if (userStoreCleared) emptyMap() else mapOf("aa" to favorite("aa")) }
    }

    private val clearTheUserStore: suspend () -> Unit = { userStoreCleared = true }

    private fun favorite(key: String) = FavoriteRelationship(
        peerNoisePublicKeyHex = key, peerNostrPublicKey = null, peerNickname = key,
        isFavorite = true, theyFavoritedUs = false, favoritedAt = 1L, lastUpdated = 1L,
    )

    private val theAppStoreFails = mockk<AppRepository>(relaxed = true) {
        coEvery { clearData() } throws IllegalStateException("the app store failed")
    }

    @Test fun wipingTheDataTakesTheCommandFeedbackWithIt() = runTest {
        // The lines name peers and channels, so they must not greet the identity that comes next.
        notices.add(Channel.Mesh, line("peers: bob"))
        wipe()(Unit)
        assertEquals(emptyList(), notices.of(Channel.Mesh))
    }

    @Test fun theThemeOnScreenFollowsTheWipe() = runTest {
        // The wipe puts the stored theme back to the system's.
        val appRepository = mockk<AppRepository>(relaxed = true) {
            coEvery { getAppTheme() } returnsMany listOf(AppTheme.DARK, AppTheme.SYSTEM)
        }
        val appEvents = InMemoryAppEventBus(defaultContextFacade)
        ObserveAppTheme(appRepository, appEvents)(Unit).test {
            assertEquals(AppTheme.DARK, awaitItem())
            wipe(appRepository, appEvents)(Unit)
            assertEquals(AppTheme.SYSTEM, awaitItem())
        }
    }

    @Test fun theThemeOnScreenFollowsAWipeThatFailsAfterTheAppStore() = runTest {
        val appRepository = mockk<AppRepository>(relaxed = true) {
            coEvery { getAppTheme() } returnsMany listOf(AppTheme.DARK, AppTheme.SYSTEM)
        }
        val appEvents = InMemoryAppEventBus(defaultContextFacade)
        ObserveAppTheme(appRepository, appEvents)(Unit).test {
            assertEquals(AppTheme.DARK, awaitItem())
            assertFailsWith<IllegalStateException> {
                wipe(appRepository, appEvents, afterTheAppStore = { error("the location store failed") })(Unit)
            }
            assertEquals(AppTheme.SYSTEM, awaitItem())
        }
    }

    @Test fun theNicknameOnScreenFollowsTheWipe() = runTest {
        val userRepository = userStore()
        val userEvents = InMemoryUserEventBus(defaultContextFacade)
        ObserveUserNickname(userRepository, userEvents)(Unit).test {
            assertEquals("alice", awaitItem())
            wipe(userRepository = userRepository, userEventBus = userEvents, whileClearing = clearTheUserStore)(Unit)
            assertEquals("anon1234", awaitItem())
        }
    }

    @Test fun theNicknameOnScreenFollowsAWipeThatFailsAfterTheUserStore() = runTest {
        val userRepository = userStore()
        val userEvents = InMemoryUserEventBus(defaultContextFacade)
        ObserveUserNickname(userRepository, userEvents)(Unit).test {
            assertEquals("alice", awaitItem())
            assertFailsWith<IllegalStateException> {
                wipe(theAppStoreFails, userRepository = userRepository, userEventBus = userEvents, whileClearing = clearTheUserStore)(Unit)
            }
            assertEquals("anon1234", awaitItem())
        }
    }

    @Test fun theFavouritesOnScreenFollowTheWipe() = runTest {
        val userRepository = userStore()
        val userEvents = InMemoryUserEventBus(defaultContextFacade)
        ObserveFavorites(userRepository, userEvents)(Unit).test {
            assertEquals(mapOf("aa" to favorite("aa")), awaitItem())
            wipe(userRepository = userRepository, userEventBus = userEvents, whileClearing = clearTheUserStore)(Unit)
            assertEquals(emptyMap(), awaitItem())
        }
    }

    @Test fun theFavouritesOnScreenFollowAWipeThatFailsAfterTheUserStore() = runTest {
        val userRepository = userStore()
        val userEvents = InMemoryUserEventBus(defaultContextFacade)
        ObserveFavorites(userRepository, userEvents)(Unit).test {
            assertEquals(mapOf("aa" to favorite("aa")), awaitItem())
            assertFailsWith<IllegalStateException> {
                wipe(theAppStoreFails, userRepository = userRepository, userEventBus = userEvents, whileClearing = clearTheUserStore)(Unit)
            }
            assertEquals(emptyMap(), awaitItem())
        }
    }

    @Test fun theWipeIsABarrierAroundEveryStoreItClears() = runTest {
        var startedInside: ChatNotices.Epoch? = null
        wipe(whileClearing = {
            // Half the stores still hold the old identity's data, so nothing may start here.
            assertEquals(true, notices.resetting)
            startedInside = notices.epoch()
            notices.add(Channel.Mesh, line("peers: bob"))
        })(Unit)
        assertEquals(false, notices.resetting, "and work is admitted again afterwards")
        assertEquals(emptyList(), notices.of(Channel.Mesh))
        // What began inside the wipe read stores that were half cleared: it files nothing either.
        withContext(startedInside!!) { notices.add(Channel.Mesh, line("peers: carol")) }
        assertEquals(emptyList(), notices.of(Channel.Mesh))
    }
}
