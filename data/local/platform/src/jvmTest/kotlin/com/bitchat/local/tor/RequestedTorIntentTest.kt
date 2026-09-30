package com.bitchat.local.tor

import com.bitchat.domain.tor.RequestedTorIntent
import com.bitchat.domain.tor.TorCapability
import com.bitchat.domain.tor.model.TorMode
import com.bitchat.local.di.commonLocal
import com.bitchat.local.di.localModule
import com.bitchat.local.prefs.impl.LocalTorPreferences
import com.russhwolf.settings.PropertiesSettings
import com.russhwolf.settings.Settings
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module

class RequestedTorIntentTest {

    private class Factory(private val backing: Properties = Properties()) : Settings.Factory {
        override fun create(name: String?): Settings = PropertiesSettings(backing)
        fun put(key: String, value: String) = backing.setProperty(key, value)
        fun has(key: String) = backing.containsKey(key)
    }

    private fun prefs(factory: Factory, default: TorMode) =
        LocalTorPreferences(settingsFactory = factory, defaultMode = { default })

    @Test
    fun `absent key with a capable platform reads on`() {
        assertEquals(TorMode.ON, prefs(Factory(), TorMode.ON).getTorMode())
    }

    @Test
    fun `absent key with an incapable platform reads off`() {
        assertEquals(TorMode.OFF, prefs(Factory(), TorMode.OFF).getTorMode())
    }

    @Test
    fun `absent key without a capability binding reads off`() {
        withGraph(capability = null) { intent ->
            assertEquals(TorMode.OFF, intent.current)
        }
    }

    @Test
    fun `a fresh Koin store with a capable platform reads on`() {
        withGraph(capability = TorCapability { true }) { intent ->
            assertEquals(TorMode.ON, intent.current)
        }
    }

    @Test
    fun `a stored ON survives an incapable platform`() {
        val factory = Factory()
        factory.put("tor_mode", TorMode.ON.name)

        assertEquals(TorMode.ON, prefs(factory, TorMode.OFF).getTorMode())
    }

    @Test
    fun `a stored OFF survives a capable platform`() {
        val factory = Factory()
        factory.put("tor_mode", TorMode.OFF.name)

        assertEquals(TorMode.OFF, prefs(factory, TorMode.ON).getTorMode())
    }

    @Test
    fun `a corrupted value falls back to the capability default`() {
        val factory = Factory()
        factory.put("tor_mode", "MAYBE")

        assertEquals(TorMode.OFF, prefs(factory, TorMode.OFF).getTorMode())
    }

    @Test
    fun `a corrupted value falls back to an on capability default`() {
        val factory = Factory()
        factory.put("tor_mode", "MAYBE")

        assertEquals(TorMode.ON, prefs(factory, TorMode.ON).getTorMode())
    }

    @Test
    fun `reading the default does not write it`() {
        // The key must stay absent, so the platform default keeps applying after an upgrade.
        val factory = Factory()
        prefs(factory, TorMode.OFF).getTorMode()

        assertEquals(false, factory.has("tor_mode"))
    }

    @Test
    fun `intent is readable synchronously and seeded from storage`() {
        val factory = Factory()
        factory.put("tor_mode", TorMode.ON.name)

        val intent = LocalRequestedTorIntent(prefs(factory, TorMode.OFF))

        assertEquals(TorMode.ON, intent.current)
        assertEquals(TorMode.ON, intent.updates.value)
    }

    @Test
    fun `setting intent persists it and publishes it`() {
        val factory = Factory()
        val intent = LocalRequestedTorIntent(prefs(factory, TorMode.OFF))

        intent.set(TorMode.ON)

        assertEquals(TorMode.ON, intent.current)
        assertEquals(TorMode.ON, intent.updates.value)
        assertEquals(TorMode.ON, prefs(factory, TorMode.OFF).getTorMode())
    }

    @Test
    fun `current reads through, so a write behind its back cannot make it stale`() {
        // TorRepo still writes the preference directly in a few places until step 1b routes them
        // through set(). Routing must not read a cached value that disagrees with storage.
        val factory = Factory()
        val intent = LocalRequestedTorIntent(prefs(factory, TorMode.OFF))
        assertEquals(TorMode.OFF, intent.current)

        prefs(factory, TorMode.OFF).setTorMode(TorMode.ON)

        assertEquals(TorMode.ON, intent.current)
    }

    private fun withGraph(
        capability: TorCapability?,
        block: (RequestedTorIntent) -> Unit,
    ) {
        val overrides = module {
            single<Settings.Factory> { Factory() }
            if (capability != null) {
                single<TorCapability> { capability }
            }
        }
        val koin = startKoin { modules(commonLocal, localModule, overrides) }.koin
        try {
            block(koin.get())
        } finally {
            stopKoin()
        }
    }
}
