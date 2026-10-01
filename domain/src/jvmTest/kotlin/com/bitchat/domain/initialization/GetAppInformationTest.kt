package com.bitchat.domain.initialization

import com.bitchat.domain.base.invoke
import com.bitchat.domain.initialization.models.AppInformation
import com.bitchat.domain.initialization.models.Version
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertSame

class GetAppInformationTest {
    @Test
    fun `answers with the information the app was built with, untouched`() = runTest {
        val information = AppInformation(
            version = Version("1.0.0", "65d65087cd41", "bitchat-tui 1.0.0 (65d65087cd41, main, clean, debug, built now)"),
            versionCode = 1,
            id = "com.bitchat.tui",
            debug = true,
        )

        assertSame(information, GetAppInformation(information)())
    }
}
