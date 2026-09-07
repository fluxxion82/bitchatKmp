package com.bitchat.nostr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RelayLogFormatterTest {
    @Test
    fun `connect attempt uses default wss port`() {
        val line = RelayLogFormatter.connectAttempt("wss://relay.primal.net", viaTor = true)

        assertEquals("SOCKS5 CONNECT to relay.primal.net:443", line)
    }

    @Test
    fun `connect attempt uses explicit port`() {
        val line = RelayLogFormatter.connectAttempt("wss://relay.example.com:9443", viaTor = true)

        assertEquals("SOCKS5 CONNECT to relay.example.com:9443", line)
    }

    @Test
    fun `connect attempt returns null for empty url`() {
        val line = RelayLogFormatter.connectAttempt("", viaTor = true)

        assertNull(line)
    }

    @Test
    fun `connected claims tor only when the socket went through tor`() {
        val line = RelayLogFormatter.connected("wss://relay.primal.net", viaTor = true)

        assertEquals("Tor connection established to relay.primal.net:443", line)
    }

    @Test
    fun `a direct connection never claims tor`() {
        val line = RelayLogFormatter.connected("wss://relay.primal.net", viaTor = false)

        assertTrue(line != null, "a direct connection should still be reported")
        assertFalse(
            line.contains("Tor connection established"),
            "a direct connection must not claim Tor, got '$line'"
        )
        assertEquals("Direct connection established to relay.primal.net:443 (not via Tor)", line)
    }

    @Test
    fun `no relay line claims tor or socks when the connection is direct`() {
        val direct = listOfNotNull(
            RelayLogFormatter.connectAttempt("wss://relay.primal.net", viaTor = false),
            RelayLogFormatter.connected("wss://relay.primal.net", viaTor = false),
            RelayLogFormatter.disconnected("wss://relay.primal.net", viaTor = false),
        )

        assertEquals(3, direct.size)
        direct.forEach { line ->
            assertFalse(
                line.contains("SOCKS", ignoreCase = true),
                "direct line must not mention SOCKS: '$line'"
            )
            assertFalse(
                line.contains("Tor connection", ignoreCase = true),
                "direct line must not claim a Tor connection: '$line'"
            )
        }
    }

    @Test
    fun `disconnect mentions socks only for tor sockets`() {
        assertEquals(
            "SOCKS connection closed for relay.primal.net:443",
            RelayLogFormatter.disconnected("wss://relay.primal.net", viaTor = true)
        )
        assertEquals(
            "Connection closed for relay.primal.net:443",
            RelayLogFormatter.disconnected("wss://relay.primal.net", viaTor = false)
        )
    }
}
