package com.bitchat.repo.repositories

import app.cash.turbine.test
import com.bitchat.domain.base.CoroutinesContextFacade
import com.bitchat.domain.chat.ObserveChannelMessages
import com.bitchat.domain.chat.eventbus.InMemoryChatEventBus
import com.bitchat.domain.chat.model.BitchatMessageType
import com.bitchat.domain.location.model.Channel
import com.bitchat.domain.location.model.GeohashChannelLevel
import com.bitchat.domain.nostr.GetPowSettings
import com.bitchat.domain.nostr.SetPowSettings
import com.bitchat.domain.nostr.eventbus.InMemoryNostrEventBus
import com.bitchat.domain.nostr.model.PowSettings
import com.bitchat.nostr.NostrClient
import com.bitchat.nostr.NostrPreferences
import com.bitchat.nostr.NostrProofOfWork
import com.bitchat.nostr.model.NostrIdentity
import io.mockk.mockk
import io.mockk.coEvery
import io.mockk.every
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The same state source both MainViewModels use to animate their PoW header. */
@OptIn(ExperimentalCoroutinesApi::class)
class PowSettingsObservationTest {
    @Test fun miningStartAndStopReachTheHeaderWithoutASettingsEvent() = runTest {
        val fixture = Fixture(UnconfinedTestDispatcher(testScheduler))
        fixture.getSettings().test {
            assertEquals(PowSettings(true, 8, false), awaitItem())
            fixture.preferences.setIsMining(true)
            assertEquals(PowSettings(true, 8, true), awaitItem())
            fixture.preferences.setIsMining(false)
            assertEquals(PowSettings(true, 8, false), awaitItem())
            fixture.setSettings(PowSettings(true, 16))
            assertEquals(PowSettings(true, 16, false), awaitItem())
        }
    }

    @Test fun changingSettingsCannotOverwriteTheMinersRuntimeState() = runTest {
        val fixture = Fixture(UnconfinedTestDispatcher(testScheduler))
        fixture.preferences.setIsMining(true)
        fixture.repository.setPowSettings(PowSettings(true, 16, isMining = false))
        assertTrue(fixture.preferences.mining.value, "a stale settings snapshot cannot stop mining")
        fixture.preferences.setIsMining(false)
        fixture.repository.setPowSettings(PowSettings(true, 12, isMining = true))
        assertFalse(fixture.preferences.mining.value, "a stale settings snapshot cannot restart mining")
    }

    @Test fun realGeohashMiningPublishesHeaderProgressAndProducesTheMessageMarker() = runTest {
        val fixture = Fixture(UnconfinedTestDispatcher(testScheduler))
        // An explicit sender identity means this call never needs the device identity store.
        val client = NostrClient(fixture.preferences, mockk())
        val identity = NostrIdentity.generate()
        fixture.getSettings().test {
            assertFalse(awaitItem().isMining)
            val send = async {
                client.createEphemeralGeohashEvent("test message", "u4pruy", identity, nickname = "me", tempId = "local-echo")
            }
            assertTrue(awaitItem().isMining)
            assertFalse(awaitItem().isMining)
            val event = send.await()
            assertTrue(NostrProofOfWork.validateDifficulty(event, 8))
            assertEquals("8", event.tags.single { it.firstOrNull() == "nonce" }.getOrNull(2))
            assertNull(client.currentlyMiningMessageId.value)
        }
    }

    @Test fun sendingALocationMessageReachesTheChatWhileMiningAndWithItsFinalMarker() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val fixture = Fixture(dispatcher)
        val client = NostrClient(fixture.preferences, mockk())
        val identity = NostrIdentity.generate()
        val events = InMemoryChatEventBus(fixture.contexts)
        // The relay and identity lookup are offline stand-ins. Message insertion, event mining,
        // final difficulty and the observer the chat uses all run their production code.
        val chat = chatRepo(
            backgroundScope, dispatcher, mutableListOf(),
            geohashIdentity = identity,
            nostrPreferences = fixture.preferences,
            chatEventBus = events,
            configureNostrClient = { boundary ->
                every { boundary.currentlyMiningMessageId } returns client.currentlyMiningMessageId
                coEvery { boundary.createEphemeralGeohashEvent(any(), any(), any(), any(), any(), any()) } coAnswers {
                    client.createEphemeralGeohashEvent(firstArg(), secondArg(), thirdArg(), arg(3), arg(4), arg(5))
                }
            },
        )
        val channel = Channel.Location(GeohashChannelLevel.CITY, "u4pruy")
        ObserveChannelMessages(chat, events)(channel).test {
            assertTrue(awaitItem().isEmpty())
            val send = async { chat.sendMessage("test message", channel, "me", BitchatMessageType.Message) }
            var messages = awaitItem()
            while (messages.none { it.isMining }) messages = awaitItem()
            val mining = messages.single()
            assertEquals("test message", mining.content)
            assertTrue(mining.id.startsWith("mining-"))
            assertNull(mining.powDifficulty)
            while (messages.none { it.powDifficulty == 8 && !it.isMining }) messages = awaitItem()
            val mined = messages.single()
            assertEquals("test message", mined.content)
            assertEquals(8, mined.powDifficulty)
            assertFalse(mined.isMining)
            assertFalse(mined.id.startsWith("mining-"))
            send.await()
            cancelAndIgnoreRemainingEvents()
        }
    }

    private class Fixture(dispatcher: CoroutineContext) {
        val preferences = Preferences()
        val contexts = object : CoroutinesContextFacade {
            override val io = dispatcher
            override val main = dispatcher
            override val default = dispatcher
            override val unconfined = dispatcher
        }
        val repository = NostrRepo(contexts, preferences)
        private val events = InMemoryNostrEventBus(contexts)
        val getSettings = GetPowSettings(repository, events)
        val setSettings = SetPowSettings(repository, events)
    }

    private class Preferences : NostrPreferences {
        private var enabled = true
        private var difficulty = 8
        private var lastUpdate = 0L
        val mining = MutableStateFlow(false)
        override fun getLastUpdateMs(): Long = lastUpdate
        override fun setLastUpdateMs(value: Long) { lastUpdate = value }
        override fun getPowEnabled(): Boolean = enabled
        override fun setPowEnabled(enabled: Boolean) { this.enabled = enabled }
        override fun getPowDifficulty(): Int = difficulty
        override fun setPowDifficulty(difficulty: Int) { this.difficulty = difficulty }
        override fun setIsMining(isMining: Boolean) { mining.value = isMining }
        override fun getIsMiningFlow(): Flow<Boolean> = mining
    }
}
