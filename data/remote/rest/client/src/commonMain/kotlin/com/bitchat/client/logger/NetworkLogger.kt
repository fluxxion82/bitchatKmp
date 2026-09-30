package com.bitchat.client.logger

import com.bitchat.domain.base.LogPolicy
import io.ktor.client.plugins.logging.*

/**
 * The Ktor logging level: request and response lines and headers, and bodies (relay lists, the
 * geocoder's answer for the user's position) only when body logging is opted in. At HEADERS Ktor
 * never formats a body, so none can reach the log, whatever it contains.
 */
fun networkLogLevel(): LogLevel = if (LogPolicy.messageBodies) LogLevel.ALL else LogLevel.HEADERS

class NetworkLogger : Logger {
    private val filteredEndpoints = setOf<String>()

    private var shouldSkipNextMessage = false

    override fun log(message: String) {
        if (message.startsWith("REQUEST:") || message.startsWith("RESPONSE:")) {
            val shouldFilter = filteredEndpoints.any { endpoint ->
                message.contains(endpoint)
            }

            if (shouldFilter) {
                shouldSkipNextMessage = true
                println(message)
                return
            }
        }

        if (message.startsWith("BODY")) {
            if (shouldSkipNextMessage) {
                println("BODY [filtered - too verbose]")
                shouldSkipNextMessage = false
                return
            }
        }

        println(message)
    }
}
