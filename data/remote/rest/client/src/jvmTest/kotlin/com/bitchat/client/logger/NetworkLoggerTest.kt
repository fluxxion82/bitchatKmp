package com.bitchat.client.logger

import com.bitchat.domain.base.LogPolicy
import io.ktor.client.plugins.logging.LogLevel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class NetworkLoggerTest {
    @AfterTest fun reset() = LogPolicy.configure(null)

    @Test fun bodiesAreNeverFormattedUnlessOptedIn() {
        // Ktor formats a body only at a level that includes it: HEADERS never does.
        assertEquals(LogLevel.HEADERS, networkLogLevel())
        assertFalse(networkLogLevel().body)
        LogPolicy.configure("1")
        assertEquals(LogLevel.ALL, networkLogLevel())
    }
}
