package com.bitchat.domain.tor.model

enum class TorState {
    OFF,
    STARTING,
    BOOTSTRAPPING,
    RUNNING,
    STOPPING,
    ERROR
}
