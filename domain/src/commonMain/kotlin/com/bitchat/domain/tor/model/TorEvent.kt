package com.bitchat.domain.tor.model

sealed class TorEvent {
    data object ModeChanged : TorEvent()
    data object StatusChanged : TorEvent()
}
