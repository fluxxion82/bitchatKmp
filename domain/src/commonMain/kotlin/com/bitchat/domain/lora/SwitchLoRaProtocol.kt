package com.bitchat.domain.lora

import com.bitchat.domain.base.Usecase
import com.bitchat.domain.chat.repository.ChatRepository
import com.bitchat.domain.lora.model.LoRaProtocolType
import com.bitchat.domain.lora.repository.LoRaSettingsRepository

class SwitchLoRaProtocol(
    private val loraSettingsRepository: LoRaSettingsRepository,
    private val chatRepository: ChatRepository,
) : Usecase<LoRaProtocolType, Boolean> {

    override suspend fun invoke(param: LoRaProtocolType): Boolean {
        loraSettingsRepository.setLoRaProtocol(param)
        return chatRepository.switchLoRaProtocol(param.name)
    }
}
