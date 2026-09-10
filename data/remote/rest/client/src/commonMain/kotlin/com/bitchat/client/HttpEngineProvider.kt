package com.bitchat.client

import io.ktor.client.engine.HttpClientEngineFactory

/**
 * Lets a platform supply the HTTP engine instead of taking the default from [getEngine].
 *
 * The routing policy has to be injected rather than compiled in. `rest:client` publishes a single
 * `jvm()` artifact and the Android app consumes it directly, so editing the shared engine to
 * enforce Tor would change Android's routing too -- blocking its traffic through bootstrap and up
 * to ten minutes of retry backoff. Registering one of these is opt-in: where nothing binds it, the
 * default engine is used and behaviour is exactly as before.
 */
fun interface HttpEngineProvider {
    fun engine(): HttpClientEngineFactory<*>
}
