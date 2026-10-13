package com.bitchat.domain.lora

import com.bitchat.domain.base.Usecase
import com.bitchat.domain.base.model.Outcome
import com.bitchat.domain.chat.repository.ChatRepository
import com.bitchat.domain.lora.model.LoRaProtocolType
import com.bitchat.domain.lora.repository.LoRaSettingsRepository

class SwitchLoRaProtocol(
    private val loraSettingsRepository: LoRaSettingsRepository,
    private val chatRepository: ChatRepository,
) : Usecase<LoRaProtocolType, Outcome<Unit>> {

    override suspend fun invoke(param: LoRaProtocolType): Outcome<Unit> {
        loraSettingsRepository.setLoRaProtocol(param)
        return chatRepository.switchLoRaProtocol(param)
    }
}
