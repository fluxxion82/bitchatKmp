package com.bitchat.domain.lora

import com.bitchat.domain.base.Usecase
import com.bitchat.domain.chat.repository.ChatRepository
import com.bitchat.domain.lora.model.LoRaRegion
import com.bitchat.domain.lora.model.LoRaTxPower
import com.bitchat.domain.lora.repository.LoRaSettingsRepository

class SetLoRaRegion(
    private val loraSettingsRepository: LoRaSettingsRepository,
    private val chatRepository: ChatRepository,
) : Usecase<SetLoRaRegion.Params, Boolean> {

    data class Params(val region: LoRaRegion, val currentTxPower: LoRaTxPower)

    override suspend fun invoke(param: Params): Boolean {
        loraSettingsRepository.setLoRaRegion(param.region)
        return chatRepository.reconfigureLoRa(param.region, param.currentTxPower)
    }
}
