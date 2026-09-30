package com.bitchat.local.tor

import com.bitchat.domain.tor.model.TorMode
import com.bitchat.local.prefs.impl.LocalTorPreferences
import com.bitchat.local.prefs.platformDefaultTorMode
import com.russhwolf.settings.PropertiesSettings
import com.russhwolf.settings.Settings
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals

class RequestedTorIntentTest {

    private class Factory(private val backing: Properties = Properties()) : Settings.Factory {
        override fun create(name: String?): Settings = PropertiesSettings(backing)
        fun put(key: String, value: String) = backing.setProperty(key, value)
        fun has(key: String) = backing.containsKey(key)
    }

    private fun prefs(factory: Factory, default: TorMode) =
        LocalTorPreferences(settingsFactory = factory, defaultMode = default)

    @Test
    fun `a fresh install on a platform that cannot proxy reads off`() {
        // The trap: an absent key used to read ON, on platforms whose HTTP engine cannot route
        // through a SOCKS proxy, so a fresh install had Nostr gated off by a choice nobody made.
        assertEquals(TorMode.OFF, prefs(Factory(), TorMode.OFF).getTorMode())
    }

    @Test
    fun `this platform default is off, since its engine cannot proxy`() {
        // Desktop, Linux (embedded) and Apple all default to OFF for the same reason; only Android,
        // whose engine does honour a proxy, still starts protected. This is the one asserted here.
        assertEquals(TorMode.OFF, platformDefaultTorMode)
    }

    @Test
    fun `a fresh Android install still reads on`() {
        assertEquals(TorMode.ON, prefs(Factory(), TorMode.ON).getTorMode())
    }

    @Test
    fun `an explicitly stored ON survives a platform default of off`() {
        val factory = Factory()
        factory.put("tor_mode", TorMode.ON.name)

        assertEquals(TorMode.ON, prefs(factory, TorMode.OFF).getTorMode())
    }

    @Test
    fun `an explicitly stored OFF survives a platform default of on`() {
        val factory = Factory()
        factory.put("tor_mode", TorMode.OFF.name)

        assertEquals(TorMode.OFF, prefs(factory, TorMode.ON).getTorMode())
    }

    @Test
    fun `a corrupted value falls back to the platform default, not to on`() {
        // Reading ON here would let a bad value recreate the trap on desktop.
        val factory = Factory()
        factory.put("tor_mode", "MAYBE")

        assertEquals(TorMode.OFF, prefs(factory, TorMode.OFF).getTorMode())
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
}
