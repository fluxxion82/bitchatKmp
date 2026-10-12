package com.bitchat.domain.nostr.eventbus

import app.cash.turbine.test
import app.cash.turbine.turbineScope
import com.bitchat.domain.base.defaultContextFacade
import com.bitchat.domain.nostr.model.NostrEvent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class InMemoryNostrEventBusTest {
    private val bus = InMemoryNostrEventBus(defaultContextFacade)

    @Test
    fun `an update returns with no collector and a later collector still gets it`() = runTest {
        bus.update(NostrEvent.PowSettingsUpdated)

        bus.events().test {
            assertEquals(NostrEvent.PowSettingsUpdated, awaitItem())
        }
    }

    @Test
    fun `every collector gets an update`() = runTest {
        turbineScope {
            val first = bus.events().testIn(backgroundScope)
            val second = bus.events().testIn(backgroundScope)

            bus.update(NostrEvent.PowSettingsUpdated)

            assertEquals(NostrEvent.PowSettingsUpdated, first.awaitItem())
            assertEquals(NostrEvent.PowSettingsUpdated, second.awaitItem())
        }
    }
}
