package com.bitchat.design.settings

import bitchatkmp.presentation.design.generated.resources.Res
import bitchatkmp.presentation.design.generated.resources.tor_not_available_in_this_build
import bitchatkmp.presentation.design.generated.resources.tor_not_supported_on_this_platform
import bitchatkmp.presentation.design.generated.resources.tor_on_but_unusable_on_this_platform
import com.bitchat.domain.tor.model.TorAvailability
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * The Tor switch is disabled for two very different reasons and they must never share a message:
 * "not available in this build" sends an iOS or embedded user hunting for a native library that is
 * present and working, when the truth is that nothing in that build can use a SOCKS proxy.
 */
class TorUnavailableMessageTest {

    @Test
    fun `no message while tor can actually protect traffic`() {
        assertNull(torUnavailableMessage(TorAvailability.AVAILABLE, torErrorMessage = null))
        assertNull(torUnavailableMessage(TorAvailability.AVAILABLE, torErrorMessage = "stale"))
    }

    @Test
    fun `a platform that cannot proxy gets its own explanation`() {
        val message = torUnavailableMessage(TorAvailability.NO_PROXY_SUPPORT, torErrorMessage = null)

        assertEquals(Res.string.tor_not_supported_on_this_platform, message?.fallback)
    }

    @Test
    fun `a platform that cannot proxy says something else again while tor is stored on`() {
        // The dead end: nothing connects, and the only way out is to turn Tor off. Saying only
        // "not supported" here reads as a contradiction against the "Tor is on" notice in chat.
        val blocked = torUnavailableMessage(
            TorAvailability.NO_PROXY_SUPPORT,
            torErrorMessage = null,
            torBlocksNostr = true,
        )

        assertEquals(Res.string.tor_on_but_unusable_on_this_platform, blocked?.fallback)
        assertNotEquals(
            torUnavailableMessage(TorAvailability.NO_PROXY_SUPPORT, torErrorMessage = null)?.fallback,
            blocked?.fallback,
        )
    }

    @Test
    fun `the two reasons never render the same string`() {
        val noProxy = torUnavailableMessage(TorAvailability.NO_PROXY_SUPPORT, torErrorMessage = null)
        val noLibrary = torUnavailableMessage(TorAvailability.NATIVE_LIBRARY_MISSING, torErrorMessage = null)

        assertNotEquals(noProxy?.fallback, noLibrary?.fallback)
        assertEquals(Res.string.tor_not_available_in_this_build, noLibrary?.fallback)
    }

    @Test
    fun `a missing library shows the concrete reason from the manager`() {
        val detail = "Tor unavailable: native library libarti_desktop.so not found on " +
                "java.library.path=/nowhere. Build it with data/remote/tor/native/build-desktop.sh."

        val message = torUnavailableMessage(TorAvailability.NATIVE_LIBRARY_MISSING, detail)

        assertEquals(detail, message?.detail, "the generic string leaves the user nothing to act on")
    }

    @Test
    fun `a stale tor error never displaces the platform explanation`() {
        // Nothing is ever started on such a platform, so anything left in the status is stale -
        // and it would replace the only message that tells the user what is really going on.
        val message = torUnavailableMessage(
            TorAvailability.NO_PROXY_SUPPORT,
            torErrorMessage = "Start failed: 1",
        )

        assertNull(message?.detail)
        assertEquals(Res.string.tor_not_supported_on_this_platform, message?.fallback)
    }
}
