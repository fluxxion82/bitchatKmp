package com.bitchat.lora.bitchat

import com.bitchat.lora.bitchat.radio.LoRaRadio
import com.bitchat.lora.radio.LoRaConfig
import com.bitchat.lora.radio.LoRaEvent
import kotlinx.coroutines.flow.Flow

/** Narrow radio lifecycle boundary, shared by the platform adapter and lifecycle tests. */
internal interface BitChatRadio {
    val events: Flow<LoRaEvent>
    val isReady: Boolean
    fun configure(config: LoRaConfig): Boolean
    fun startReceiving()
    fun send(data: ByteArray): Boolean
    suspend fun shutdown()
}

internal class PlatformBitChatRadio(private val radio: LoRaRadio) : BitChatRadio {
    override val events get() = radio.events
    override val isReady get() = radio.isReady
    override fun configure(config: LoRaConfig) = radio.configure(config)
    override fun startReceiving() = radio.startReceiving()
    override fun send(data: ByteArray) = radio.send(data)
    override suspend fun shutdown() = radio.shutdown()
}
