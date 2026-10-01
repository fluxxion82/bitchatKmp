@file:OptIn(ExperimentalAtomicApi::class)

package com.bitchat.client.harness

import com.bitchat.client.OwnedDarwinSession
import com.bitchat.client.OwnedSessionEngineFactory
import io.ktor.client.engine.HttpClientEngineFactory
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Test-only observation of the production owned-session factory. */
internal class OwnedSessionRegistry {
    private val sessions = AtomicReference<List<OwnedDarwinSession>>(emptyList())

    fun engineFactory(): (Boolean) -> HttpClientEngineFactory<*> = { OwnedSessionEngineFactory(::add) }
    fun factory(): OwnedSessionEngineFactory = OwnedSessionEngineFactory(::add)
    fun all(): List<OwnedDarwinSession> = sessions.load()
    fun single(): OwnedDarwinSession = all().single()
    suspend fun invalidateAll(bound: Duration = 5.seconds) = all().map { it.invalidateAndAwait(bound) }

    private fun add(session: OwnedDarwinSession) {
        while (true) {
            val current = sessions.load()
            if (sessions.compareAndSet(current, current + session)) return
        }
    }
}
