package com.bitchat.client.logger

import io.ktor.client.plugins.logging.*

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