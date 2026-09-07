package com.bitchat.repo.tor

import com.bitchat.repo.repositories.TorRepo
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertFalse

class TorRelayProxyStatusTest {

    @Test
    fun `nothing is routed through tor while the proxy is not ready`() {
        val torRepo = mockk<TorRepo>()
        every { torRepo.isProxyReady() } returns false

        assertFalse(TorRelayProxyStatus(torRepo).isRoutingThroughTor())
    }
}
