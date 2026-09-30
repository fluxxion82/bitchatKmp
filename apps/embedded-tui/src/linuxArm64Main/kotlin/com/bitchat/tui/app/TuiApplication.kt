package com.bitchat.tui.app

import com.bitchat.bluetooth.di.bluetoothModule
import com.bitchat.client.di.clientModule
import com.bitchat.domain.di.domainModule
import com.bitchat.domain.initialization.InitializeApplication
import com.bitchat.embedded.BuildIdentity
import com.bitchat.embedded.di.LoRaProtocolSelector
import com.bitchat.embedded.di.buildConfigModule
import com.bitchat.local.di.commonLocal
import com.bitchat.local.di.localModule
import com.bitchat.lora.bitchat.di.bitChatLoraModule
import com.bitchat.lora.di.loraProtocolManagerModule
import com.bitchat.lora.meshcore.di.meshcoreLoraModule
import com.bitchat.lora.meshtastic.di.meshtasticLoraModule
import com.bitchat.nostr.di.nostrModule
import com.bitchat.repo.di.commonRepoModule
import com.bitchat.repo.di.repoModule
import com.bitchat.tor.di.torModule
import com.bitchat.viewmodel.di.viewModelModule
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.context.startKoin
import org.koin.mp.KoinPlatform.getKoin

/**
 * The Koin graph: the same modules as the Compose app (`apps/embedded/.../Main.kt`), in the same
 * order. None of those is UI-only (the Compose app's UI is wired outside Koin), so none is dropped.
 */
class TuiApplication(identity: BuildIdentity) : KoinComponent {
    init {
        val initialProtocol = LoRaProtocolSelector.getPreferredProtocol()
        startKoin {
            modules(
                buildConfigModule(identity, appId = "com.bitchat.tui"),
                domainModule,
                commonLocal,
                localModule,
                clientModule,
                commonRepoModule,
                repoModule,
                viewModelModule,
                nostrModule,
                bluetoothModule,
                bitChatLoraModule,
                meshtasticLoraModule,
                meshcoreLoraModule,
                loraProtocolManagerModule(initialProtocol),
                torModule,
            )
        }
    }

    val initializeApplication: InitializeApplication by inject()

    /**
     * The view models the screens need, created after initialisation. Koin's `viewModel {}`
     * definitions are factories and there is no ViewModelStoreOwner here, so the ones made now live
     * for the whole process. Locations is made per visit ([TuiViewModels.newLocations]): its view
     * model polls for a location fix every five seconds for as long as it exists, as in the Compose
     * app, where it lives only while its sheet is open.
     */
    fun viewModels() = getKoin().tuiViewModels()
}
