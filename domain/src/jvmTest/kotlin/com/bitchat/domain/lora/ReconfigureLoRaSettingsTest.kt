package com.bitchat.domain.lora

import com.bitchat.domain.chat.repository.ChatRepository
import com.bitchat.domain.lora.model.LoRaRegion
import com.bitchat.domain.lora.model.LoRaTxPower
import com.bitchat.domain.lora.repository.LoRaSettingsRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ReconfigureLoRaSettingsTest {
    @Test
    fun `region returns runtime result after saving preference even on failure`() = runTest {
        for (ready in listOf(false, true)) {
            val settings = mockk<LoRaSettingsRepository>()
            var saved: LoRaRegion? = null
            every { settings.setLoRaRegion(any()) } answers { saved = firstArg() }
            val chat = mockk<ChatRepository>()
            coEvery { chat.reconfigureLoRa(LoRaRegion.EU_868, LoRaTxPower.LOW) } coAnswers {
                assertEquals(LoRaRegion.EU_868, saved)
                ready
            }
            val result: Any = SetLoRaRegion(settings, chat)(SetLoRaRegion.Params(LoRaRegion.EU_868, LoRaTxPower.LOW))
            assertEquals(ready, result)
            assertEquals(LoRaRegion.EU_868, saved)
        }
    }

    @Test
    fun `power returns runtime result after saving preference even on failure`() = runTest {
        for (ready in listOf(false, true)) {
            val settings = mockk<LoRaSettingsRepository>()
            var saved: LoRaTxPower? = null
            every { settings.setLoRaTxPower(any()) } answers { saved = firstArg() }
            val chat = mockk<ChatRepository>()
            coEvery { chat.reconfigureLoRa(LoRaRegion.US_915, LoRaTxPower.LOW) } coAnswers {
                assertEquals(LoRaTxPower.LOW, saved)
                ready
            }
            val result: Any = SetLoRaTxPower(settings, chat)(SetLoRaTxPower.Params(LoRaTxPower.LOW, LoRaRegion.US_915))
            assertEquals(ready, result)
            assertEquals(LoRaTxPower.LOW, saved)
        }
    }

    @Test
    fun `reconfiguration cancellation propagates after saving the preference`() = runTest {
        val settings = mockk<LoRaSettingsRepository>(relaxed = true)
        val chat = mockk<ChatRepository>()
        coEvery { chat.reconfigureLoRa(any(), any()) } throws CancellationException("Cancelled")
        assertFailsWith<CancellationException> {
            SetLoRaRegion(settings, chat)(SetLoRaRegion.Params(LoRaRegion.EU_868, LoRaTxPower.LOW))
        }
        assertFailsWith<CancellationException> {
            SetLoRaTxPower(settings, chat)(SetLoRaTxPower.Params(LoRaTxPower.LOW, LoRaRegion.US_915))
        }
        verify { settings.setLoRaRegion(LoRaRegion.EU_868) }
        verify { settings.setLoRaTxPower(LoRaTxPower.LOW) }
    }
}
