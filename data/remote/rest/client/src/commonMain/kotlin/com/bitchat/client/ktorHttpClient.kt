package com.bitchat.client

import com.bitchat.client.logger.NetworkLogger
import com.bitchat.client.mapper.addClientTypeParameters
import com.bitchat.client.mapper.toBaseUrl
import com.bitchat.client.model.ClientType
import com.bitchat.domain.initialization.models.AppInformation
import com.bitchat.tor.TorManager
import io.ktor.client.*
import io.ktor.client.engine.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import org.koin.mp.KoinPlatform.getKoin
import kotlin.time.Duration.Companion.seconds

const val CONNECT_TIMEOUT = 30
const val READ_TIMEOUT = 30
const val WRITE_TIMEOUT = 30
const val CACHE_SIZE = 1 * 1024 * 1024L // 1MiB
const val SECOND_MILLISECONDS = 1000L

fun ktorWebSocketHttpClient(
    torManager: TorManager? = null,
    engine: HttpClientEngineFactory<*> = getEngine(getKoin().get<AppInformation>().debug, torManager),
): HttpClient {
    return HttpClient(engine) {
        install(Logging) {
            logger = NetworkLogger()
            level = LogLevel.ALL
        }

        install(WebSockets) {
            pingInterval = 30.seconds  // Keep connections alive with 30-second pings
        }

        install(HttpTimeout) {
            requestTimeoutMillis = CONNECT_TIMEOUT * SECOND_MILLISECONDS
            connectTimeoutMillis = CONNECT_TIMEOUT * SECOND_MILLISECONDS
            socketTimeoutMillis = READ_TIMEOUT * SECOND_MILLISECONDS
        }
    }
}

fun ktorHttpClient(
    clientType: ClientType,
    interceptors: List<(HttpRequestBuilder) -> Unit>,
    torManager: TorManager? = null,
    engine: HttpClientEngineFactory<*> = getEngine(getKoin().get<AppInformation>().debug, torManager),
): HttpClient {
    return HttpClient(engine) {
        install(ContentNegotiation) {
            json(
                json = kotlinx.serialization.json.Json {
                    prettyPrint = true
                    isLenient = true
                    encodeDefaults = true
                    ignoreUnknownKeys = true
                    explicitNulls = false
                    allowSpecialFloatingPointValues = true
                },
            )
            json(
                json = kotlinx.serialization.json.Json {
                    prettyPrint = true
                    isLenient = true
                    encodeDefaults = true
                    ignoreUnknownKeys = true
                    explicitNulls = false
                    allowSpecialFloatingPointValues = true
                },
                contentType = ContentType.Text.Any
            )
        }
        install(Logging) {
            logger = NetworkLogger()
            level = LogLevel.ALL
        }

        install(WebSockets) {
            pingInterval = 30.seconds  // Keep connections alive with 30-second pings
        }

        defaultRequest {
            host = clientType.toBaseUrl().substringAfter("https://")
            url {
                protocol = URLProtocol.HTTPS
            }
            contentType(ContentType.Application.Json)
        }

        install(HttpTimeout) {
            requestTimeoutMillis = CONNECT_TIMEOUT * SECOND_MILLISECONDS
            connectTimeoutMillis = CONNECT_TIMEOUT * SECOND_MILLISECONDS
            socketTimeoutMillis = READ_TIMEOUT * SECOND_MILLISECONDS
        }
    }.apply {
        plugin(HttpSend).intercept { request ->
            if (request.method == HttpMethod.Get) {
                clientType.addClientTypeParameters()
            }

            request.header(HttpHeaders.ContentType, ContentType.Application.Json)
            request.header(HttpHeaders.Accept, ContentType.Application.Json)

            interceptors.forEach { interceptor ->
                interceptor(request)
            }

            execute(request)
        }
    }
}

expect fun getEngine(isDebug: Boolean, torManager: TorManager? = null): HttpClientEngineFactory<*>
