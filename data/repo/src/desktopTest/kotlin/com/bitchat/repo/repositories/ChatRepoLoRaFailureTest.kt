package com.bitchat.repo.repositories

import com.bitchat.domain.base.model.Outcome
import com.bitchat.domain.lora.model.LoRaProtocolType
import com.bitchat.domain.lora.model.LoRaRegion
import com.bitchat.domain.lora.model.LoRaTxPower
import com.bitchat.domain.lora.model.failure.LoRaFailure
import com.bitchat.lora.LoRaProtocol
import com.bitchat.lora.LoRaProtocolManager
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.bitchat.lora.LoRaProtocolType as RadioProtocol

@OptIn(ExperimentalCoroutinesApi::class)
class ChatRepoLoRaFailureTest {

    @Test
    fun withoutAManagerEveryLoRaChangeSaysNoRadio() {
        for (lora in listOf<() -> LoRaProtocol?>({ null }, { radio() })) withRepo(lora = { lora() }) { repo ->
            assertEquals(Outcome.Error(LoRaFailure.NoRadio), repo.switchLoRaProtocol(LoRaProtocolType.BITCHAT))
            assertEquals(Outcome.Error(LoRaFailure.NoRadio), repo.reconfigureLoRa(LoRaRegion.US_915, LoRaTxPower.HIGH))
        }
    }

    @Test
    fun aProtocolTheAppDidNotRegisterIsRefusedAndTheRunningRadioLeftAlone() {
        val bitChat = radio()
        lateinit var manager: LoRaProtocolManager
        withRepo(lora = { manager(bitChat, registered = setOf(RadioProtocol.BITCHAT)).also { manager = it } }) { repo ->
            assertEquals(Outcome.Success(Unit), repo.switchLoRaProtocol(LoRaProtocolType.BITCHAT))
            clearMocks(bitChat, answers = false)

            assertEquals(Outcome.Error(LoRaFailure.NotRegistered), repo.switchLoRaProtocol(LoRaProtocolType.MESHCORE))

            assertEquals(RadioProtocol.BITCHAT, manager.activeType.value)
            assertTrue(manager.isReady)
            coVerify(exactly = 0) { bitChat.start(any()) }
            coVerify(exactly = 0) { bitChat.stop() }
        }
    }

    @Test
    fun aProtocolThatDoesNotStartIsNotApplied() =
        withRepo(lora = { manager(radio(starts = false)) }) { repo ->
            assertEquals(Outcome.Error(LoRaFailure.NotApplied), repo.switchLoRaProtocol(LoRaProtocolType.BITCHAT))
        }

    @Test
    fun theActiveRadioThatKeepsItsOwnSettingsIsNamedWhenReconfigured() =
        withRepo(lora = { manager(radio(), meshtastic = radio(takesSettings = false)) }) { repo ->
            assertEquals(Outcome.Success(Unit), repo.switchLoRaProtocol(LoRaProtocolType.MESHTASTIC))

            assertEquals(
                Outcome.Error(LoRaFailure.KeepsOwnRadioSettings(LoRaProtocolType.MESHTASTIC)),
                repo.reconfigureLoRa(LoRaRegion.US_915, LoRaTxPower.HIGH),
            )
        }

    @Test
    fun aReconfigurationThatFailsOnARadioThatTakesSettingsIsNotApplied() =
        withRepo(lora = { manager(radio(starts = false)) }) { repo ->
            assertEquals(
                Outcome.Error(LoRaFailure.NotApplied),
                repo.reconfigureLoRa(LoRaRegion.US_915, LoRaTxPower.HIGH),
            )
        }

    private fun radio(starts: Boolean = true, takesSettings: Boolean = true): LoRaProtocol {
        var ready = false
        return mockk(relaxed = true) {
            every { isReady } answers { ready }
            every { supportsRadioConfiguration } returns takesSettings
            every { peers } returns MutableStateFlow(emptyList())
            every { incomingMessages } returns emptyFlow()
            coEvery { start(any()) } coAnswers { ready = starts; starts }
            coEvery { stop() } coAnswers { ready = false }
        }
    }

    private fun TestScope.manager(
        bitChat: LoRaProtocol,
        meshtastic: LoRaProtocol? = null,
        registered: Set<RadioProtocol> = RadioProtocol.entries.toSet(),
    ) = LoRaProtocolManager(
        lazy { bitChat },
        lazy { meshtastic ?: error("Meshtastic must not be resolved") },
        lazy { error("MeshCore must not be resolved") },
        backgroundScope,
        readinessTimeoutMs = 500,
        availableTypes = registered,
    )

    private fun withRepo(
        lora: TestScope.() -> LoRaProtocol?,
        block: suspend TestScope.(ChatRepo) -> Unit,
    ) = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            block(chatRepo(scope, dispatcher, mutableListOf(), lora = lora()))
        } finally {
            scope.cancel()
        }
    }
}
