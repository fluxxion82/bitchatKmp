package com.bitchat.desktop

import com.bitchat.bluetooth.di.bluetoothModule
import com.bitchat.client.di.clientModule
import com.bitchat.client.BaseApiClient
import com.bitchat.client.NostrGeoRelayClient
import com.bitchat.desktop.di.desktopDataModules
import com.bitchat.desktop.net.desktopNetworkModule
import com.bitchat.domain.initialization.InitializeApplication
import com.bitchat.domain.initialization.models.Version
import com.bitchat.local.di.commonLocal
import com.bitchat.local.di.localModule
import com.bitchat.lora.LoRaProtocolType
import com.bitchat.repo.di.commonRepoModule
import com.bitchat.tor.di.torModule
import com.bitchat.viewmodel.permissions.PermissionsErrorViewModel
import org.koin.dsl.module
import org.koin.test.verify.definition
import org.koin.test.verify.injectedParameters
import org.koin.test.verify.verify
import kotlin.test.Test
import kotlin.test.assertTrue

class DesktopModulesTest {
    private val modules = desktopDataModules(
        appId = "com.bitchat.desktop",
        initialProtocol = LoRaProtocolType.BITCHAT,
    )

    @Test
    fun desktopGraphContainsTheRouteAwareDependenciesInOrder() {
        assertTrue(modules.any { it === torModule })
        assertTrue(modules.any { it === clientModule })
        assertTrue(modules.any { it === commonLocal })
        assertTrue(modules.any { it === localModule })
        assertTrue(modules.any { it === commonRepoModule })
        assertTrue(modules.any { it === bluetoothModule })

        val clientIndex = modules.indexOfFirst { it === clientModule }
        val networkIndex = modules.indexOfFirst { it === desktopNetworkModule }
        assertTrue(networkIndex > clientIndex)
    }

    @Test
    fun desktopGraphVerifies() {
        module { includes(modules) }.verify(
            extraTypes = listOf(Version::class, Boolean::class, List::class),
            injections = injectedParameters(
                definition<InitializeApplication>(Set::class),
                definition<NostrGeoRelayClient>(BaseApiClient::class),
                definition<PermissionsErrorViewModel>(List::class),
            ),
        )
    }
}
