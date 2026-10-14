package com.bitchat.local.service

import com.bitchat.domain.location.model.LocationFixInfo
import com.bitchat.domain.location.model.LocationUnavailableException
import com.bitchat.domain.location.model.LocationUnavailableException.Reason
import com.bitchat.domain.tor.RequestedTorIntent
import com.bitchat.domain.tor.model.TorMode
import com.russhwolf.settings.PropertiesSettings
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class IpLocationServiceTest {
    private class Factory : Settings.Factory {
        private val properties = java.util.Properties()
        override fun create(name: String?): Settings = PropertiesSettings(properties)
    }

    private class Intent(var mode: TorMode) : RequestedTorIntent {
        override val current get() = mode
        override val updates: StateFlow<TorMode> get() = MutableStateFlow(mode)
    }

    private val answer = """{"latitude":37.7749,"longitude":-122.4194}"""

    private fun service(
        factory: Settings.Factory = Factory(),
        mode: TorMode = TorMode.OFF,
        environment: (String) -> String? = { null },
        httpGet: suspend (url: String, admit: () -> Boolean) -> String?,
    ) = IpLocationService(factory, Intent(mode), environment, httpGet)

    @Test
    fun `asks the first provider and reports a fix from the address`() = runTest {
        val asked = mutableListOf<String>()
        val service = service { url, _ -> asked += url; answer }

        val fix = service.getCurrentLocation()

        assertEquals(37.7749, fix.lat)
        assertEquals(-122.4194, fix.lon)
        assertEquals(listOf("https://ipwho.is/"), asked)
        assertEquals(LocationFixInfo.Source.IP_ADDRESS, service.lastFixInfo()?.source)
    }

    @Test
    fun `asks nobody while Tor is requested`() = runTest {
        var asked = 0
        val service = service(mode = TorMode.ON) { _, _ -> asked++; answer }

        val failure = assertFailsWith<LocationUnavailableException> { service.getCurrentLocation() }

        assertEquals(Reason.SUPPRESSED_BY_POLICY, failure.reason)
        assertEquals(0, asked)
    }

    @Test
    fun `asks nobody while the environment names a proxy`() = runTest {
        for (name in listOf("https_proxy", "HTTPS_PROXY", "all_proxy", "ALL_PROXY", "http_proxy")) {
            var asked = 0
            val service = service(environment = { if (it == name) "socks5h://127.0.0.1:9050" else null }) { _, _ ->
                asked++
                answer
            }

            val failure = assertFailsWith<LocationUnavailableException>(name) { service.getCurrentLocation() }

            assertEquals(Reason.SUPPRESSED_BY_POLICY, failure.reason, name)
            assertEquals(0, asked, name)
        }
    }

    @Test
    fun `no answer from any provider is a failed lookup`() = runTest {
        val asked = mutableListOf<String>()
        val service = service { url, _ -> asked += url; null }

        val failure = assertFailsWith<LocationUnavailableException> { service.getCurrentLocation() }

        assertEquals(Reason.LOOKUP_FAILED, failure.reason)
        assertEquals(3, asked.size)
    }

    @Test
    fun `a kept fix is served while Tor is requested and is gone after a clear`() = runTest {
        val factory = Factory()
        service(factory) { _, _ -> answer }.getCurrentLocation()

        val later = service(factory, TorMode.ON) { _, _ -> error("must not ask") }
        assertEquals(37.7749, later.getCurrentLocation().lat)

        later.clearCachedLocation()
        val afterClear = service(factory, TorMode.ON) { _, _ -> error("must not ask") }
        assertFailsWith<LocationUnavailableException> { afterClear.getCurrentLocation() }
    }

    @Test
    fun `a board does not report permission`() = runTest {
        assertFalse(service { _, _ -> null }.hasLocationPermission())
    }
}
