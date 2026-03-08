package com.bitchat.domain.lora

import com.bitchat.domain.base.Usecase
import com.bitchat.domain.lora.model.LoRaSettings
import com.bitchat.domain.lora.repository.LoRaSettingsRepository

class GetLoRaSettings(
    private val loraSettingsRepository: LoRaSettingsRepository,
) : Usecase<Unit, LoRaSettings> {

    override suspend fun invoke(param: Unit): LoRaSettings {
        return loraSettingsRepository.getLoRaSettings()
    }
}
