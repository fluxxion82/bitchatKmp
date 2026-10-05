package com.bitchat.desktop.di

import com.bitchat.bluetooth.di.bluetoothModule
import com.bitchat.client.di.clientModule
import com.bitchat.desktop.net.desktopNetworkModule
import com.bitchat.domain.di.domainModule
import com.bitchat.local.di.commonLocal
import com.bitchat.local.di.localModule
import com.bitchat.lora.LoRaProtocolType
import com.bitchat.lora.bitchat.di.bitChatLoraModule
import com.bitchat.lora.di.loraProtocolManagerModule
import com.bitchat.lora.meshtastic.di.meshtasticLoraModule
import com.bitchat.nostr.di.nostrModule
import com.bitchat.repo.di.commonRepoModule
import com.bitchat.repo.di.repoModule
import com.bitchat.tor.di.torModule
import com.bitchat.viewmodel.di.viewModelModule
import org.koin.core.module.Module

fun desktopDataModules(appId: String, initialProtocol: LoRaProtocolType): List<Module> = listOf(
    desktopBuildConfigModule(appId),
    domainModule,
    commonLocal,
    localModule,
    clientModule,
    desktopNetworkModule,
    commonRepoModule,
    repoModule,
    viewModelModule,
    nostrModule,
    bluetoothModule,
    torModule,
    bitChatLoraModule,
    meshtasticLoraModule,
    loraProtocolManagerModule(initialProtocol),
)
