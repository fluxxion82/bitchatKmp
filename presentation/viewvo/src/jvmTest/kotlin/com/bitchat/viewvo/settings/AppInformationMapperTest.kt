package com.bitchat.viewvo.settings

import com.bitchat.domain.initialization.models.AppInformation
import com.bitchat.domain.initialization.models.Version
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppInformationMapperTest {
    private fun information(name: String = "1.0.0", additionalInfo: String = "") = AppInformation(
        version = Version(name = name, build = "0", additionalInfo = additionalInfo),
        versionCode = 1,
        id = "com.bitchat.test",
        debug = false,
    )

    @Test
    fun theVersionIsTheBuildsName() {
        assertEquals("1.0.0", information(name = "1.0.0").toVersionLabel())
        assertEquals("2.7.3", information(name = " 2.7.3 ").toVersionLabel())
    }

    @Test
    fun aBlankVersionIsUnknownRatherThanBlank() {
        assertEquals(UNKNOWN_VERSION, information(name = "").toVersionLabel())
        assertEquals(UNKNOWN_VERSION, information(name = "   ").toVersionLabel())
    }

    @Test
    fun theIdentityIsGivenAsTheBuildWroteIt() {
        val line = "bitchat-embedded 1.0.0 (65d65087cd41, main, clean, debug, built 2026-09-06T20:25:33-07:00)"

        assertEquals(line, information(additionalInfo = line).toBuildIdentity())
        assertEquals(line, information(additionalInfo = "\n$line ").toBuildIdentity())
    }

    @Test
    fun aBuildWithoutAnIdentityHasNone() {
        assertNull(information(additionalInfo = "").toBuildIdentity())
        assertNull(information(additionalInfo = " \n").toBuildIdentity())
    }
}
