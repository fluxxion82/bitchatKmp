package com.bitchat.domain.lora

import com.bitchat.domain.base.Usecase
import com.bitchat.domain.lora.repository.LoRaSettingsRepository

class SetShowLoRaPeers(
    private val loraSettingsRepository: LoRaSettingsRepository,
) : Usecase<Boolean, Unit> {

    override suspend fun invoke(param: Boolean) {
        loraSettingsRepository.setShowLoRaPeers(param)
    }
}
