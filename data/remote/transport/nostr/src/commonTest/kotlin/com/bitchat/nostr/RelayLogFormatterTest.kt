package com.bitchat.nostr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RelayLogFormatterTest {
    @Test
    fun `connect attempt uses default wss port`() {
        val line = RelayLogFormatter.connectAttempt("wss://relay.primal.net")

        assertEquals("SOCKS5 CONNECT to relay.primal.net:443", line)
    }

    @Test
    fun `connect attempt uses explicit port`() {
        val line = RelayLogFormatter.connectAttempt("wss://relay.example.com:9443")

        assertEquals("SOCKS5 CONNECT to relay.example.com:9443", line)
    }

    @Test
    fun `connect attempt returns null for empty url`() {
        val line = RelayLogFormatter.connectAttempt("")

        assertNull(line)
    }
}
