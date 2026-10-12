package com.bitchat.domain.lora

import com.bitchat.domain.base.Usecase
import com.bitchat.domain.lora.model.LoRaProtocolType
import com.bitchat.domain.lora.repository.LoRaSettingsRepository

class GetAvailableLoRaProtocols(
    private val loraSettingsRepository: LoRaSettingsRepository,
) : Usecase<Unit, List<LoRaProtocolType>> {

    override suspend fun invoke(param: Unit): List<LoRaProtocolType> {
        return loraSettingsRepository.getAvailableLoRaProtocols()
    }
}
