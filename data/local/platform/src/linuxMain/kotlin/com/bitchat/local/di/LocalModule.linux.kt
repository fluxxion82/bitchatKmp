package com.bitchat.local.di

import com.bitchat.local.identity.DomainInspector
import com.bitchat.local.identity.LedgerStore
import com.bitchat.local.identity.LinuxDomainInspector
import com.bitchat.local.identity.LinuxLedgerStore
import com.bitchat.local.prefs.EncryptionSettingsFactory
import com.bitchat.local.prefs.LinuxEncryptionSettingsFactory
import com.bitchat.local.prefs.LinuxFileSettings
import com.bitchat.local.prefs.ensureDirectory
import com.bitchat.local.service.GeocoderService
import com.bitchat.local.service.LinuxLocationService
import com.bitchat.local.service.LinuxSettingsService
import com.bitchat.local.service.LocationService
import com.bitchat.local.service.SettingsService
import com.bitchat.local.service.impl.StubGeocoderService
import com.russhwolf.settings.Settings
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import org.koin.dsl.module
import platform.posix.getenv

@OptIn(ExperimentalForeignApi::class)
actual val localModule = module {
    single<Settings.Factory> {
        LinuxSettingsFactory()
    }

    single<EncryptionSettingsFactory> {
        LinuxEncryptionSettingsFactory()
    }

    // createdAtStart, and this is load-bearing rather than a preference. LinuxDomainInspector
    // takes its reading of the identity domain in its constructor, and that reading is only
    // meaningful if it happens before this application has written anything: its own first file
    // makes the domain inhabited, and an inhabited domain with no ledger refuses to mint. Built
    // eagerly, while Koin assembles the graph, the reading is taken before any preference store
    // exists, so a genuine first run is still seen as one.
    single<DomainInspector>(createdAtStart = true) { LinuxDomainInspector() }

    single<LedgerStore> { LinuxLedgerStore() }

    single<GeocoderService> { StubGeocoderService() }

    single<SettingsService> { LinuxSettingsService() }

    single<LocationService> { LinuxLocationService() }
}

/**
 * Linux-specific Settings.Factory implementation.
 * Creates file-based settings in ~/.bitchat/settings/
 */
@OptIn(ExperimentalForeignApi::class)
class LinuxSettingsFactory : Settings.Factory {
    private val settingsDir: String by lazy {
        val home = getenv("HOME")?.toKString() ?: "/tmp"
        val baseDir = "$home/.bitchat"
        val dir = "$baseDir/settings"
        ensureDirectory(baseDir)
        ensureDirectory(dir)
        dir
    }

    override fun create(name: String?): Settings {
        val filename = name ?: "default"
        return LinuxFileSettings("$settingsDir/$filename.prefs")
    }
}
