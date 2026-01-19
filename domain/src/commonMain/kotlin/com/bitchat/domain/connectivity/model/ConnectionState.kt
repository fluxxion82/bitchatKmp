package com.bitchat.domain.connectivity.model

enum class ConnectionState {
    CONNECTED,
    DISCONNECTED
}

fun ConnectionState.isConnected() = this == ConnectionState.CONNECTED
