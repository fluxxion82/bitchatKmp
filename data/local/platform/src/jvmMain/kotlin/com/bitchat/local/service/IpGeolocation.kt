package com.bitchat.local.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URI

internal object IpGeolocation {
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 5_000
    private const val MAX_BODY_BYTES = 16 * 1024

    suspend fun get(url: String, admit: () -> Boolean): String? = withContext(Dispatchers.IO) {
        val connection = (URI(url).toURL().openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
            // A redirect would be a second request that nothing has admitted.
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
        }
        try {
            // Reading the response code is what connects, so that is the last moment to refuse.
            if (!admit() || connection.responseCode != HttpURLConnection.HTTP_OK) return@withContext null
            val body = connection.inputStream.readNBytes(MAX_BODY_BYTES + 1)
            if (body.size > MAX_BODY_BYTES) null else body.decodeToString()
        } finally {
            connection.disconnect()
        }
    }
}
