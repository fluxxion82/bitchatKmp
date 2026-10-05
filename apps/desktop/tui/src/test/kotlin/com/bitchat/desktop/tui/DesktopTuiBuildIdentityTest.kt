package com.bitchat.desktop.tui

import com.bitchat.domain.initialization.models.AppInformation
import com.bitchat.lora.LoRaProtocolType
import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopTuiBuildIdentityTest {

    @Test
    fun desktopTuiDataModulesPassBuildIdentityToAppInformation() {
        val application = koinApplication(createEagerInstances = false) {
            modules(desktopTuiDataModules(LoRaProtocolType.BITCHAT))
        }

        try {
            val information = application.koin.get<AppInformation>()

            assertEquals(desktopTuiVersionLine(), information.version.additionalInfo)
            assertEquals(DesktopTuiBuildInfo.version, information.version.name)
            assertEquals(DesktopTuiBuildInfo.gitSha, information.version.build)
            assertEquals("com.bitchat.desktop.tui", information.id)
        } finally {
            application.close()
        }
    }
}
