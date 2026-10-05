package com.bitchat.desktop.tui

import com.bitchat.desktop.di.desktopDataModules
import com.bitchat.domain.initialization.models.Version
import com.bitchat.lora.LoRaProtocolType
import com.bitchat.repo.initialization.headlessUserStateModule
import org.koin.core.module.Module

internal fun desktopTuiVersionLine(): String =
    "bitchat-tui ${DesktopTuiBuildInfo.version} (${DesktopTuiBuildInfo.gitSha})"

internal fun desktopTuiVersion() = Version(
    name = DesktopTuiBuildInfo.version,
    build = DesktopTuiBuildInfo.gitSha,
    additionalInfo = desktopTuiVersionLine(),
)

internal fun desktopTuiDataModules(initialProtocol: LoRaProtocolType): List<Module> =
    desktopDataModules(
        appId = "com.bitchat.desktop.tui",
        initialProtocol = initialProtocol,
        version = desktopTuiVersion(),
    ) + headlessUserStateModule
