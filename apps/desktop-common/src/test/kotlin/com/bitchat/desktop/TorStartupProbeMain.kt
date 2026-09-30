package com.bitchat.desktop

import com.bitchat.domain.tor.RequestedTorIntent
import com.bitchat.domain.tor.TorCapability
import com.bitchat.local.di.commonLocal
import com.bitchat.local.di.localModule
import com.bitchat.repo.di.commonRepoModule
import com.bitchat.tor.TorManager
import com.bitchat.tor.di.torModule
import com.russhwolf.settings.PropertiesSettings
import com.russhwolf.settings.Settings
import java.util.Properties
import kotlin.system.exitProcess
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * The child JVM of [TorStartupGraphTest]: starts the real Tor-related part of the desktop graph and prints what the
 * requested-Tor-intent chain made of it. A separate JVM per case because `TorManager` decides once per JVM whether the
 * Arti library loaded.
 *
 * `TorStartupProbeMain <stored tor_mode: absent|ON|OFF> <tor data dir>`; every result line starts with `PROBE `.
 */
object TorStartupProbeMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val stored = args[0]
        val torDataDir = args[1]
        val backing = Properties().apply { if (stored != "absent") setProperty("tor_mode", stored) }
        val settings = object : Settings.Factory {
            override fun create(name: String?): Settings = PropertiesSettings(backing)
        }
        val koin = startKoin {
            modules(
                commonLocal,
                localModule,
                commonRepoModule,
                torModule,
                // The only replacements: storage in memory instead of java.util.prefs, and a scratch Tor data
                // directory instead of ~/.bitchat/tor. Everything that decides the default is the real thing.
                module {
                    single<Settings.Factory> { settings }
                    single(named("torDataDir")) { torDataDir }
                },
            )
        }.koin
        // MutableRequestedTorIntent is created at start, so the chain below has already run by now.
        val intent = koin.get<RequestedTorIntent>()
        println("PROBE intent.current=${intent.current}")
        println("PROBE intent.updates=${intent.updates.value}")
        println("PROBE tor.available=${koin.get<TorManager>().isAvailable}")
        println("PROBE capability=${koin.get<TorCapability>().canRouteTraffic()}")
        println("PROBE key.stored=${backing.getProperty("tor_mode")}")
        stopKoin()
        exitProcess(0)
    }
}
