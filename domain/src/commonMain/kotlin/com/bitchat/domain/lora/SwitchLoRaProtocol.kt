package com.bitchat.domain.lora

import com.bitchat.domain.base.Usecase
import com.bitchat.domain.chat.repository.ChatRepository
import com.bitchat.domain.lora.model.LoRaProtocolType
import com.bitchat.domain.lora.repository.LoRaSettingsRepository

class SwitchLoRaProtocol(
    private val loraSettingsRepository: LoRaSettingsRepository,
    private val chatRepository: ChatRepository,
) : Usecase<LoRaProtocolType, Unit> {

    override suspend fun invoke(param: LoRaProtocolType) {
        loraSettingsRepository.setLoRaProtocol(param)
        chatRepository.switchLoRaProtocol(param.name)
    }
}
