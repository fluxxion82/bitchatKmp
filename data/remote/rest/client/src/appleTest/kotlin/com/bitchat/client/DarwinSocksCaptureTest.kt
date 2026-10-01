package com.bitchat.client

import com.bitchat.client.harness.CaptureCases
import com.bitchat.client.harness.CaptureSubject
import com.bitchat.client.harness.harnessProvider
import kotlin.test.Test

/**
 * A1 spike harness (docs/plans/2026-09-30-item2-apple-tor.md): drives the real
 * [RouteAwareClientProvider] with Ktor Darwin's own session and SOCKS mapping against a capture
 * proxy and the local-resolution leak detector. Outcomes are data for the go/no-go decision;
 * assertions are never weakened to pass. The SOCKS address-type/name assertions run always; the
 * DNS assertions depend on the owner's resolver file and skip LOUDLY (never pass) when it is absent.
 * The case bodies live in [CaptureCases]; [OwnedSessionCaptureTest] runs them against the owned factory.
 */
class DarwinSocksCaptureTest {
    private fun ktorSession() = CaptureSubject("ktor-session", providerFactory = { intent, port -> harnessProvider(intent, port) })

    @Test
    fun positiveControl_directRequestIsSeenByLeakDetector() = CaptureCases.positiveControl(ktorSession())

    @Test
    fun httpsGetReachesSocksByDomainName() = CaptureCases.httpsGet(ktorSession())

    @Test
    fun wssConnectReachesSocksByDomainName() = CaptureCases.wssConnect(ktorSession())

    @Test
    fun refusedSocksFailsBoundedWithoutLocalResolution() = CaptureCases.refused(ktorSession())

    @Test
    fun deadSocksPortFailsBoundedWithoutLocalResolution() = CaptureCases.deadPort(ktorSession())

    @Test
    fun httpRedirectFollowsThroughSocksByDomainName() = CaptureCases.httpRedirect(ktorSession())

    @Test
    fun webSocketReconnectAfterCloseReachesSocksAgain() = CaptureCases.wssReconnect(ktorSession())

    @Test
    fun webSocketReconnectAfterConnectEofReachesSocksAgain() = CaptureCases.wssReconnectAfterConnectEof(ktorSession())

    @Test
    fun establishedWebSocketReconnectAfterTunnelDropReachesSocksAgain() = CaptureCases.establishedWsReconnectAfterTunnelDrop(ktorSession())

    @Test
    fun heldHandshakeStaysPendingWithoutLocalResolution() = CaptureCases.heldHandshake(ktorSession())
}
