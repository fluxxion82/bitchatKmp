package com.bitchat.domain.tor

import com.bitchat.domain.tor.eventbus.TorEventBus
import com.bitchat.domain.tor.model.TorAvailability
import com.bitchat.domain.tor.model.TorMode
import com.bitchat.domain.tor.model.TorState
import com.bitchat.domain.tor.model.TorStatus
import com.bitchat.domain.tor.repository.TorRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The header shows its Tor indicator only when the reported mode is ON, so what this reports
 * decides whether a user can see that Tor is not working.
 */
class GetTorStatusModeTest {

    private val eventBus = mockk<TorEventBus>(relaxed = true).also {
        every { it.events() } returns emptyFlow()
    }

    private fun repository(
        requested: TorMode,
        effective: TorMode,
        availability: TorAvailability,
        status: TorStatus,
    ): TorRepository = mockk<TorRepository>(relaxed = true).also {
        coEvery { it.getStoredTorMode() } returns requested
        coEvery { it.getTorMode() } returns effective
        every { it.torAvailability() } returns availability
        coEvery { it.getTorStatus() } returns status
    }

    @Test
    fun `a failed start still reports ON, so the indicator stays visible`() = runTest {
        // The defect: the effective mode reads OFF whenever Tor is not working, so the indicator
        // vanished at exactly the moment it had something to say, and a broken Tor looked the same
        // as a Tor that had never been switched on.
        val repository = repository(
            requested = TorMode.ON,
            effective = TorMode.OFF,
            availability = TorAvailability.NATIVE_LIBRARY_MISSING,
            status = TorStatus(state = TorState.ERROR, errorMessage = "no native library"),
        )

        val status = GetTorStatus(repository, eventBus)(Unit).first()

        assertEquals(TorMode.ON, status.mode)
        assertEquals(false, status.running, "and it must render as not connected")
    }

    @Test
    fun `a healthy tor reports ON and running`() = runTest {
        val repository = repository(
            requested = TorMode.ON,
            effective = TorMode.ON,
            availability = TorAvailability.AVAILABLE,
            status = TorStatus(state = TorState.RUNNING, running = true, bootstrapPercent = 100),
        )

        val status = GetTorStatus(repository, eventBus)(Unit).first()

        assertEquals(TorMode.ON, status.mode)
        assertEquals(true, status.running)
    }

    @Test
    fun `tor that was never asked for reports OFF`() = runTest {
        val repository = repository(
            requested = TorMode.OFF,
            effective = TorMode.OFF,
            availability = TorAvailability.AVAILABLE,
            status = TorStatus(),
        )

        assertEquals(TorMode.OFF, GetTorStatus(repository, eventBus)(Unit).first().mode)
    }

    @Test
    fun `a stored ON reports OFF where the engine cannot proxy at all`() = runTest {
        // Nothing was ever routed on such a build, so an indicator would claim protection that
        // never existed. Same rule as the settings switch.
        val repository = repository(
            requested = TorMode.ON,
            effective = TorMode.OFF,
            availability = TorAvailability.NO_PROXY_SUPPORT,
            status = TorStatus(),
        )

        assertEquals(TorMode.OFF, GetTorStatus(repository, eventBus)(Unit).first().mode)
    }
}
