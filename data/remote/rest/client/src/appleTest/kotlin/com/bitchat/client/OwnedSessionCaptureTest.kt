@file:OptIn(ExperimentalForeignApi::class)

package com.bitchat.client

import com.bitchat.client.harness.CaptureCases
import com.bitchat.client.harness.CaptureSubject
import com.bitchat.client.harness.OwnedSessionRegistry
import com.bitchat.client.harness.harnessProvider
import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A2: the A1 cases rerun through the REAL provider with the OWNED session factory
 * ([com.bitchat.client.harness.OwnedSessionEngineFactory]): configuration built by us from the
 * route, forwarding delegate, `usePreconfiguredSession`. Every case must still route by name with
 * zero local queries, and every session the factory created is invalidated at the end of the case.
 */
class OwnedSessionCaptureTest {
    private fun ownedSession(): CaptureSubject {
        val sessions = OwnedSessionRegistry()
        return CaptureSubject(
            label = "owned-session",
            providerFactory = { intent, port -> harnessProvider(intent, port, sessions.engineFactory()) },
            cleanup = {
                assertTrue(sessions.all().isNotEmpty(), "the provider never used the owned session factory")
                val invalidations = sessions.invalidateAll()
                println("[owned-session] ${invalidations.size} session(s) torn down: $invalidations")
                invalidations.forEach { assertTrue(it.acknowledged, "owned session invalidation was not acknowledged: $it") }
            },
        )
    }

    @Test
    fun positiveControl_directRequestIsSeenByLeakDetector() = CaptureCases.positiveControl(ownedSession())

    @Test
    fun httpsGetReachesSocksByDomainName() = CaptureCases.httpsGet(ownedSession())

    @Test
    fun wssConnectReachesSocksByDomainName() = CaptureCases.wssConnect(ownedSession())

    @Test
    fun refusedSocksFailsBoundedWithoutLocalResolution() = CaptureCases.refused(ownedSession())

    @Test
    fun deadSocksPortFailsBoundedWithoutLocalResolution() = CaptureCases.deadPort(ownedSession())

    @Test
    fun httpRedirectFollowsThroughSocksByDomainName() = CaptureCases.httpRedirect(ownedSession())

    @Test
    fun webSocketReconnectAfterCloseReachesSocksAgain() = CaptureCases.wssReconnect(ownedSession())

    @Test
    fun webSocketReconnectAfterConnectEofReachesSocksAgain() = CaptureCases.wssReconnectAfterConnectEof(ownedSession())

    @Test
    fun establishedWebSocketReconnectAfterTunnelDropReachesSocksAgain() = CaptureCases.establishedWsReconnectAfterTunnelDrop(ownedSession())

    @Test
    fun heldHandshakeStaysPendingWithoutLocalResolution() = CaptureCases.heldHandshake(ownedSession())

    /** (B1.5, B2.1) The built configuration's dictionary: SOCKS keys only for a Tor route, never an exception. */
    @Test
    fun torRouteConfigurationCarriesSocksAndNoExceptions() {
        val configuration = OwnedSessionConfigurations.build(DarwinRoute.Socks("127.0.0.1", 9150))
        val dictionary = assertNotNull(configuration.connectionProxyDictionary, "proxy dictionary")
        assertEquals(1, OwnedSessionConfigurations.intValue(dictionary[OwnedSessionConfigurations.SOCKS_ENABLE]), "SOCKSEnable")
        assertEquals("127.0.0.1", dictionary[OwnedSessionConfigurations.SOCKS_PROXY].toString(), "SOCKSProxy")
        assertEquals(9150, OwnedSessionConfigurations.intValue(dictionary[OwnedSessionConfigurations.SOCKS_PORT]), "SOCKSPort")
        assertEquals(0, OwnedSessionConfigurations.intValue(dictionary[OwnedSessionConfigurations.EXCLUDE_SIMPLE_HOSTNAMES]), "ExcludeSimpleHostnames")
        val exceptions = dictionary[OwnedSessionConfigurations.EXCEPTIONS_LIST]
        assertTrue(exceptions is List<*> && exceptions.isEmpty(), "ExceptionsList must be empty, was $exceptions")
        assertCommonHardening(configuration)
    }

    @Test
    fun directRouteConfigurationHasNoProxyAndNoExceptions() {
        val configuration = OwnedSessionConfigurations.build(DarwinRoute.Direct)
        assertNull(configuration.connectionProxyDictionary, "direct routes retain the system proxy policy")
        assertCommonHardening(configuration)
    }

    private fun assertCommonHardening(configuration: platform.Foundation.NSURLSessionConfiguration) {
        assertNull(configuration.URLCache, "URLCache")
        assertNull(configuration.HTTPCookieStorage, "HTTPCookieStorage")
        assertFalse(configuration.HTTPShouldSetCookies, "HTTPShouldSetCookies")
        assertNull(configuration.URLCredentialStorage, "URLCredentialStorage")
        assertFalse(configuration.waitsForConnectivity, "waitsForConnectivity")
    }
}
