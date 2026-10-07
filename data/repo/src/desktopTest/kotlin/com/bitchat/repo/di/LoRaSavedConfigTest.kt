package com.bitchat.repo.di

import com.bitchat.domain.app.model.ActiveState
import com.bitchat.domain.app.model.UserState
import com.bitchat.domain.base.CoroutineScopeFacade
import com.bitchat.domain.base.CoroutinesContextFacade
import com.bitchat.domain.lora.model.LoRaBandwidth
import com.bitchat.domain.lora.model.LoRaRegion
import com.bitchat.domain.lora.model.LoRaTxPower
import com.bitchat.domain.user.eventbus.UserEventBus
import com.bitchat.domain.user.repository.UserRepository
import com.bitchat.bluetooth.service.BluetoothMeshService
import com.bitchat.local.prefs.LoRaPreferences
import com.bitchat.local.prefs.UserPreferences
import com.bitchat.lora.LoRaProtocol
import com.bitchat.lora.LoRaProtocolManager
import com.bitchat.lora.radio.LoRaConfig
import com.bitchat.repo.repositories.ChatRepo
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LoRaSavedConfigTest {
    @Test
    fun explicitSelectionUsesSavedRegionWhenBootstrapWasDisabled() = runTest {
        val configs = mutableListOf<LoRaConfig>()
        var ready = false
        val radio = mockk<LoRaProtocol>(relaxed = true) {
            every { isReady } answers { ready }
            every { supportsRadioConfiguration } returns true
            every { peers } returns MutableStateFlow(emptyList())
            every { incomingMessages } returns emptyFlow()
            coEvery { start(any()) } coAnswers { configs += firstArg<LoRaConfig>(); ready = true; true }
            coEvery { stop() } coAnswers { ready = false }
        }
        val manager = LoRaProtocolManager(lazy { radio }, lazy { error("unused") }, lazy { error("unused") }, backgroundScope)
        val preferences = mockk<LoRaPreferences> {
            every { isLoRaEnabled() } returns false
            every { getLoRaRegion() } returns LoRaRegion.EU_868
            every { getTxPower() } returns LoRaTxPower.LOW
            every { getBandwidth() } returns LoRaBandwidth.KHZ_500
        }
        val users = mockk<UserRepository> { coEvery { getUserState() } returns UserState.Active(ActiveState.Settings) }
        val mesh = mockk<BluetoothMeshService> { every { myPeerID } returns "0123456789abcdef" }
        val events = mockk<UserEventBus> { every { events() } returns emptyFlow() }
        LoRaAppInitializer(manager, users, preferences, events, mesh, backgroundScope).initialize()
        runCurrent()
        assertTrue(configs.isEmpty())

        // Isolate unrelated Bluetooth/Nostr background work; the actual ChatRepo LoRa
        // entry point and real manager remain under test.
        val unusedScope = CoroutineScope(Job().apply { cancel() })
        val scopes = mockk<CoroutineScopeFacade> {
            every { applicationScope } returns unusedScope
            every { nostrScope } returns unusedScope
        }
        val contexts = mockk<CoroutinesContextFacade> { every { io } returns coroutineContext }
        val userPrefs = mockk<UserPreferences> {
            every { clearPeerDisplayNames() } returns Unit
            every { clearAllPeerIDMappings() } returns Unit
            every { updateFavorites<Any?>(any()) } answers {
                firstArg<(Map<String, com.bitchat.domain.user.model.FavoriteRelationship>) -> com.bitchat.local.prefs.FavoritesUpdate<Any?>>()
                    .invoke(emptyMap()).result
            }
            every { getAllFavorites() } returns emptyMap()
            every { getAllLastReadTimestamps() } returns emptyMap()
        }
        val repo = ChatRepo(scopes, contexts,
            mesh = mockk(relaxed = true), nostr = mockk(relaxed = true),
            nostrPreferences = mockk(relaxed = true), nostrClient = mockk(relaxed = true),
            nostrRelay = mockk(relaxed = true), geohashAliasCache = mockk(relaxed = true),
            geohashConversationCache = mockk(relaxed = true), channelPreferences = mockk(relaxed = true),
            userPreferences = userPrefs, blockListPreferences = mockk(relaxed = true),
            participantTracker = mockk(relaxed = true), locationEventBus = mockk(relaxed = true),
            chatEventBus = mockk(relaxed = true), userRepository = users,
            appRepository = mockk(relaxed = true), userEventBus = events,
            connectEventBus = mockk(relaxed = true), lora = manager, loraPreferences = preferences)
        assertTrue(repo.switchLoRaProtocol("BITCHAT"))
        assertEquals(868_125_000L, configs.single().frequency)
        assertEquals(10, configs.single().txPower)
        assertEquals(500_000L, configs.single().bandwidth)
        assertEquals(9, configs.single().spreadingFactor)
        assertEquals(0x12, configs.single().syncWord)

        // Changing region and power afterwards keeps the saved bandwidth.
        assertTrue(repo.reconfigureLoRa(LoRaRegion.US_915, LoRaTxPower.HIGH))
        assertEquals(2, configs.size)
        assertEquals(915_125_000L, configs.last().frequency)
        assertEquals(20, configs.last().txPower)
        assertEquals(500_000L, configs.last().bandwidth)
        manager.stop()
    }
}
