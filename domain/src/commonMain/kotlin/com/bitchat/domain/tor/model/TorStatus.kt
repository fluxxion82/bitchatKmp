package com.bitchat.domain.tor.model

data class TorStatus(
    val mode: TorMode = TorMode.OFF,
    val running: Boolean = false,
    val bootstrapPercent: Int = 0,
    val lastLogLine: String = "",
    val state: TorState = TorState.OFF,
    val socksPort: Int = 9050,
    val errorMessage: String? = null
)
