package com.bitchat.domain.lora

import com.bitchat.domain.base.Usecase
import com.bitchat.domain.chat.repository.ChatRepository
import com.bitchat.domain.lora.model.LoRaRegion
import com.bitchat.domain.lora.model.LoRaTxPower
import com.bitchat.domain.lora.repository.LoRaSettingsRepository

class SetLoRaTxPower(
    private val loraSettingsRepository: LoRaSettingsRepository,
    private val chatRepository: ChatRepository,
) : Usecase<SetLoRaTxPower.Params, Unit> {

    data class Params(val txPower: LoRaTxPower, val currentRegion: LoRaRegion)

    override suspend fun invoke(param: Params) {
        loraSettingsRepository.setLoRaTxPower(param.txPower)
        chatRepository.reconfigureLoRa(param.currentRegion, param.txPower)
    }
}
