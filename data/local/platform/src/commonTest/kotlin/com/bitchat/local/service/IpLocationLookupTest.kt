package com.bitchat.local.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IpLocationLookupTest {
    @Test
    fun `named proxy environment disables an IP lookup`() {
        listOf("https_proxy", "HTTPS_PROXY", "all_proxy", "ALL_PROXY", "http_proxy").forEach { name ->
            assertTrue(proxyNamedInEnvironment { candidate -> if (candidate == name) "proxy" else null })
        }
        assertFalse(proxyNamedInEnvironment { name -> if (name == "https_proxy") " " else null })
        assertFalse(proxyNamedInEnvironment { name -> if (name == "no_proxy" || name == "HTTP_PROXY") "proxy" else null })
        assertFalse(proxyNamedInEnvironment { null })
    }

    @Test
    fun `providers are asked in order and first parsed answer wins`() = runTest {
        val asked = mutableListOf<String>()
        val point = lookUpIpLocation(
            admit = { true },
            providers = listOf("https://one", "https://two", "https://three"),
            get = { url, _ ->
                asked += url
                if (url == "https://two") """{"latitude":37.7,"longitude":-122.4}""" else null
            },
        )

        assertEquals(listOf("https://one", "https://two"), asked)
        assertEquals(37.7, point?.lat)
    }

    @Test
    fun `admission is checked before every provider and stops lookup`() = runTest {
        var checks = 0
        val asked = mutableListOf<String>()
        val point = lookUpIpLocation(
            admit = { ++checks == 1 },
            providers = listOf("https://one", "https://two"),
            get = { url, _ -> asked += url; null },
        )

        assertNull(point)
        assertEquals(listOf("https://one"), asked)
    }

    @Test
    fun `exception null and unusable body fall through`() = runTest {
        val point = lookUpIpLocation(
            admit = { true },
            providers = listOf("https://one", "https://two", "https://three", "https://four"),
            get = { url, _ ->
                when (url) {
                    "https://one" -> throw IllegalStateException()
                    "https://two" -> null
                    "https://three" -> "marker-body-no-coordinate"
                    else -> """{"lat":1.0,"lon":2.0}"""
                }
            },
        )

        assertEquals(1.0, point?.lat)
    }

    @Test
    fun `cancellation from a provider propagates`() = runTest {
        assertFailsWith<CancellationException> {
            lookUpIpLocation({ true }, get = { _, _ -> throw CancellationException("caller cancelled") })
        }
    }

    @Test
    fun `provider logs omit response text and coordinates`() = runTest {
        val lines = mutableListOf<String>()
        lookUpIpLocation(
            admit = { true },
            providers = listOf("https://one"),
            get = { _, _ -> "marker-private-body latitude 44.444 longitude -55.555" },
            log = lines::add,
        )

        assertTrue(IP_LOCATION_PROVIDERS.all { it.startsWith("https://") })
        assertEquals(listOf("IpLocation: https://one no usable coordinate"), lines)
        assertTrue(lines.none { it.contains("marker-private-body") || it.contains("44.444") || it.contains("-55.555") })
    }

    @Test
    fun `provider logs identify answers failures and refused lookups`() = runTest {
        val lines = mutableListOf<String>()
        lookUpIpLocation(
            admit = { true },
            providers = listOf("https://one", "https://two", "https://three"),
            get = { url, _ ->
                when (url) {
                    "https://one" -> throw IllegalStateException()
                    "https://two" -> null
                    else -> """{"lat":1,"lon":2}"""
                }
            },
            log = lines::add,
        )
        assertEquals(
            listOf(
                "IpLocation: https://one IllegalStateException",
                "IpLocation: https://two no usable coordinate",
                "IpLocation: https://three answered",
            ),
            lines,
        )

        lines.clear()
        lookUpIpLocation(
            admit = { false },
            providers = listOf("https://one"),
            get = { _, _ -> error("must not ask") },
            log = lines::add,
        )
        assertEquals(listOf("IpLocation: lookup not permitted, https://one not asked"), lines)
    }
}
