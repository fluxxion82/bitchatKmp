package com.bitchat.domain.tor

import com.bitchat.domain.tor.eventbus.TorEventBus
import com.bitchat.domain.tor.model.TorAvailability
import com.bitchat.domain.tor.model.TorEvent
import com.bitchat.domain.tor.model.TorMode
import com.bitchat.domain.tor.model.TorStatus
import com.bitchat.domain.tor.repository.TorRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DisableTorRouteRecoveryTest {
    @Test
    fun `OFF publishes native stop and intent without waiting for direct retirement`() = runBlocking {
        val intent = InMemoryIntent(TorMode.ON)
        val repository = RecordingTorRepository()
        val events = RecordingEvents()
        var waitedForDirectRetirements: Boolean? = null
        val lifecycle = object : TorRouteLifecycle {
            override suspend fun transition(
                waitForDirectRetirements: Boolean,
                publishPolicy: suspend () -> Unit,
            ) {
                waitedForDirectRetirements = waitForDirectRetirements
                publishPolicy()
            }
        }

        DisableTor(repository, intent, events, lifecycle)(Unit)

        assertFalse(waitedForDirectRetirements ?: true)
        assertTrue(repository.disabled, "the native Tor stop was not published")
        assertEquals(TorMode.OFF, intent.current, "direct-route recovery cannot observe OFF")
        assertEquals(TorEvent.ModeChanged, events.last)
    }

    private class InMemoryIntent(initial: TorMode) : MutableRequestedTorIntent {
        private val state = MutableStateFlow(initial)
        override val current: TorMode get() = state.value
        override val updates = state
        override fun set(mode: TorMode) { state.value = mode }
    }

    private class RecordingEvents : TorEventBus {
        var last: TorEvent? = null
        override fun events(): Flow<TorEvent> = emptyFlow()
        override suspend fun update(event: TorEvent) { last = event }
    }

    private class RecordingTorRepository : TorRepository {
        var disabled = false
        override fun torAvailability() = TorAvailability.AVAILABLE
        override suspend fun getTorStatus() = TorStatus()
        override suspend fun getTorMode() = TorMode.OFF
        override suspend fun getStoredTorMode() = TorMode.OFF
        override suspend fun setTorMode(mode: TorMode) = Unit
        override suspend fun getSocksProxyAddress(): Pair<String, Int>? = null
        override fun isProxyReady() = false
        override suspend fun enable() = Unit
        override suspend fun disable() { disabled = true }
        override suspend fun clearData() = Unit
    }
}
