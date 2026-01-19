package com.bitchat.domain.connectivity.model

enum class ConnectionEvent {
    CONNECTED,
    DISCONNECTED
}

fun ConnectionEvent.isConnected() = this == ConnectionEvent.CONNECTED
