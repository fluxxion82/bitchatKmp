package com.bitchat.local.service

import com.bitchat.domain.location.model.GeoPoint
import com.bitchat.domain.location.model.LocationFixInfo
import com.bitchat.domain.tor.RequestedTorIntent
import com.bitchat.domain.tor.model.TorMode
import com.russhwolf.settings.PropertiesSettings
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.TestTimeSource
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class IpLocationSourceTest {
    private class Factory : Settings.Factory {
        private val properties = java.util.Properties()
        override fun create(name: String?): Settings = PropertiesSettings(properties)
    }

    private class Intent(var mode: TorMode = TorMode.OFF) : RequestedTorIntent {
        override val current get() = mode
        override val updates: StateFlow<TorMode> get() = MutableStateFlow(mode)
    }

    private class ClearingFactory : Settings.Factory {
        private val properties = java.util.Properties()
        private val delegate = PropertiesSettings(properties)
        lateinit var source: IpLocationSource
        private var firstWrite = true
        var clearingThread: Thread? = null

        override fun create(name: String?): Settings = object : Settings by delegate {
            override fun putDouble(key: String, value: Double) {
                delegate.putDouble(key, value)
                if (firstWrite) {
                    firstWrite = false
                    clearingThread = thread { source.clear() }
                    clearingThread!!.join(2_000)
                    assertTrue(clearingThread!!.isAlive, "clear finished while the write was in progress")
                }
            }
        }

        fun hasNoLocationKeys(): Boolean = listOf(
            "last_fix_lat",
            "last_fix_lon",
            "last_fix_observed_at",
            "last_fix_source",
        ).none(properties::containsKey)
    }

    @Test
    fun `freshness and retry use monotonic time only`() = runTest {
        val time = TestTimeSource()
        var wall = 1_000_000L
        var calls = 0
        val source = IpLocationSource(Factory(), Intent(), {
            calls++
            if (calls == 1) GeoPoint(1.0, 2.0) else null
        }, timeSource = time, wallClock = { wall })

        assertEquals(1.0, source.fix()?.lat)
        wall += 24 * 60 * 60 * 1000L
        assertEquals(1.0, source.fix()?.lat)
        time += 15.minutes - 1.seconds
        source.fix()
        assertEquals(1, calls)
        time += 1.seconds
        source.fix()
        assertEquals(2, calls)
        wall -= 2 * 24 * 60 * 60 * 1000L
        source.fix()
        assertEquals(2, calls)
    }

    @Test
    fun `last fix age follows wall clock and lookup can be disabled`() = runTest {
        val time = TestTimeSource()
        var wall = 1_000L
        var calls = 0
        val source = IpLocationSource(Factory(), Intent(), {
            calls++
            GeoPoint(1.0, 2.0)
        }, lookupEnabled = { false }, timeSource = time, wallClock = { wall })

        assertNull(source.fix())
        assertEquals(0, calls)
        source.rememberDeviceFix { GeoPoint(1.0, 2.0) }
        wall += 6_000
        assertEquals(6_000, source.lastFixInfo()?.ageMillis)
    }

    @Test
    fun `failed lookups wait sixty monotonic seconds before retrying`() = runTest {
        val time = TestTimeSource()
        var calls = 0
        val source = IpLocationSource(Factory(), Intent(), {
            calls++
            null
        }, timeSource = time)

        source.fix()
        source.fix()
        time += 59.seconds
        source.fix()
        assertEquals(1, calls)
        time += 1.seconds
        source.fix()
        assertEquals(2, calls)
    }

    @Test
    fun `a device fix is kept`() = runTest {
        val factory = Factory()
        val source = IpLocationSource(factory, Intent(TorMode.ON), { null })

        assertEquals(GeoPoint(1.0, 2.0), source.rememberDeviceFix { GeoPoint(1.0, 2.0) })

        assertEquals(LocationFixInfo.Source.DEVICE, source.lastFixInfo()?.source)
        val nextRun = IpLocationSource(factory, Intent(TorMode.ON), { null })
        assertEquals(GeoPoint(1.0, 2.0), nextRun.fix())
        assertEquals(LocationFixInfo.Source.DEVICE, nextRun.lastFixInfo()?.source)
    }

    @Test
    fun `a fix from an earlier run is served but never counts as fresh`() = runTest {
        val factory = Factory()
        IpLocationSource(factory, Intent(), { GeoPoint(1.0, 2.0) }).fix()

        val time = TestTimeSource()
        var calls = 0
        val nextRun = IpLocationSource(factory, Intent(), {
            calls++
            null
        }, timeSource = time)

        // Reading its provenance first must not make it look freshly fetched.
        assertEquals(LocationFixInfo.Source.IP_ADDRESS, nextRun.lastFixInfo()?.source)
        assertEquals(GeoPoint(1.0, 2.0), nextRun.fix())
        assertEquals(1, calls)
        time += 61.seconds
        assertEquals(GeoPoint(1.0, 2.0), nextRun.fix())
        assertEquals(2, calls)
    }

    @Test
    fun `a device fix read across a clear is returned but not kept`() = runTest {
        val factory = Factory()
        val source = IpLocationSource(factory, Intent(TorMode.ON), { null })

        val fix = source.rememberDeviceFix {
            source.clear()
            GeoPoint(1.0, 2.0)
        }

        assertEquals(GeoPoint(1.0, 2.0), fix)
        assertNull(source.lastFixInfo())
        assertNull(IpLocationSource(factory, Intent(TorMode.ON), { null }).fix())
    }

    @Test
    fun `clear cannot be overtaken by a lookup write`() = runTest {
        val factory = ClearingFactory()
        val source = IpLocationSource(factory, Intent(), { GeoPoint(1.0, 2.0) })
        factory.source = source

        source.fix()
        factory.clearingThread!!.join(2_000)

        assertTrue(!factory.clearingThread!!.isAlive, "clear did not finish after the write")
        assertNull(source.lastFixInfo())
        assertTrue(factory.hasNoLocationKeys())
    }
}
