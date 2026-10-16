package com.bitchat.local.prefs

import app.cash.turbine.test
import com.bitchat.local.prefs.impl.LocalNostrPreferences
import com.russhwolf.settings.PropertiesSettings
import com.russhwolf.settings.Settings
import java.util.Properties
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class LocalNostrPreferencesMiningTest {

    @Test
    fun miningThatEndsRightAfterTheFirstValueIsShown() = runTest {
        val store = EndsMiningOnFirstRead(PropertiesSettings(Properties()))
        val preferences = LocalNostrPreferences(factory(store))
        preferences.setIsMining(true)
        store.onFirstMiningRead = { preferences.setIsMining(false) }

        // Mining also ends as the first value reaches the collector, for a flag that is not read from the store.
        var ended = false
        preferences.getIsMiningFlow()
            .onEach { if (it && !ended) { ended = true; preferences.setIsMining(false) } }
            .test {
                assertEquals(true, awaitItem())
                assertEquals(false, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
    }

    @Test
    fun aRestartDoesNotShowMiningThatWasUnderWay() = runTest {
        val store = PropertiesSettings(Properties())
        LocalNostrPreferences(factory(store)).setIsMining(true)

        LocalNostrPreferences(factory(store)).getIsMiningFlow().test {
            assertEquals(false, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun factory(settings: Settings) = object : Settings.Factory {
        override fun create(name: String?): Settings = settings
    }

    /** Runs [onFirstMiningRead] once, right after the first read of the mining flag. */
    private class EndsMiningOnFirstRead(private val delegate: Settings) : Settings by delegate {
        var onFirstMiningRead: (() -> Unit)? = null

        override fun getBoolean(key: String, defaultValue: Boolean): Boolean {
            val value = delegate.getBoolean(key, defaultValue)
            if (key == "pow_is_mining") onFirstMiningRead?.also { onFirstMiningRead = null }?.invoke()
            return value
        }
    }
}
