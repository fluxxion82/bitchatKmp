package com.bitchat.tor

import kotlin.test.Test

class ArtiLogLineTest {

    /**
     * The first real Arti line ("SOCKS5 CONNECT to ...") crashed the iOS app: the text was passed
     * to NSLog for a `%@` conversion. Logging must survive any text, format specifiers included.
     */
    @Test
    fun loggingAnArtiLineDoesNotCrash() {
        logArtiLine("SOCKS5 CONNECT to relay.damus.io:443")
        logArtiLine("bootstrapped 100% %@ %s %d %n")
        logArtiLine("")
    }
}
