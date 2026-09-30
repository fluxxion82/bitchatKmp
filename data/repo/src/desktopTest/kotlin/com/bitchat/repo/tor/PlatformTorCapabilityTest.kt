package com.bitchat.repo.tor

import com.bitchat.domain.tor.TorCapability
import com.bitchat.repo.di.commonRepoModule
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin

class PlatformTorCapabilityTest {

    @Test
    fun `cannot route when the HTTP engine does not support a Tor proxy`() {
        assertFalse(capability(engineSupportsTorProxy = false, torAvailable = true).canRouteTraffic())
    }

    @Test
    fun `cannot route when no Tor manager is available`() {
        assertFalse(capability(engineSupportsTorProxy = true, torAvailable = null).canRouteTraffic())
    }

    @Test
    fun `common repository graph is incapable when the Tor manager is absent`() {
        val koin = startKoin { modules(commonRepoModule) }.koin
        try {
            assertFalse(koin.get<TorCapability>().canRouteTraffic())
        } finally {
            stopKoin()
        }
    }

    @Test
    fun `cannot route when Tor is unavailable`() {
        assertFalse(capability(engineSupportsTorProxy = true, torAvailable = false).canRouteTraffic())
    }

    @Test
    fun `can route only when the engine and Tor are both available`() {
        assertTrue(capability(engineSupportsTorProxy = true, torAvailable = true).canRouteTraffic())
    }

    @Test
    fun `checks Tor availability when capability is queried`() {
        var torAvailable = false
        val capability = PlatformTorCapability(
            engineSupportsTorProxy = true,
            isTorAvailable = { torAvailable },
        )

        assertFalse(capability.canRouteTraffic())
        torAvailable = true
        assertTrue(capability.canRouteTraffic())
    }

    private fun capability(
        engineSupportsTorProxy: Boolean,
        torAvailable: Boolean?,
    ) = PlatformTorCapability(
        engineSupportsTorProxy = engineSupportsTorProxy,
        isTorAvailable = { torAvailable },
    )
}
