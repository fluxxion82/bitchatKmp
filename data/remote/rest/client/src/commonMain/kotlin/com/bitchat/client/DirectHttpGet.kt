package com.bitchat.client

import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** One GET from this device's own address, or nothing. */
class DirectHttpGet(
    private val routes: RouteAwareClientProvider,
    private val timeout: Duration = 10.seconds,
    private val maxBytes: Int = 16 * 1024,
) {
    suspend fun get(url: String, admit: () -> Boolean): String? = supervisorScope {
        val request = async {
            routes.withDirectClient { client ->
                if (!admit()) return@withDirectClient null
                withTimeoutOrNull(timeout) {
                    client.prepareGet(url).execute { response ->
                        if (response.status != HttpStatusCode.OK) return@execute null
                        response.bodyAsChannel().readAtMost(maxBytes)
                    }
                }
            }
        }
        try {
            request.await()
        } catch (_: CancellationException) {
            // The route was switched under the request. A cancelled caller still ends cancelled:
            // this scope is.
            null
        }
    }
}

internal suspend fun ByteReadChannel.readAtMost(limit: Int): String? {
    val bytes = ByteArray(limit + 1)
    var offset = 0
    while (offset < bytes.size) {
        val read = readAvailable(bytes, offset, bytes.size - offset)
        if (read == -1) {
            // A channel closed with a failure reads as its end too, and keeps nothing it held.
            closedCause?.let { throw it }
            break
        }
        offset += read
    }
    return if (offset > limit) null else bytes.copyOf(offset).decodeToString()
}
