package com.bitchat.domain.lora.model.failure

import com.bitchat.domain.base.model.failure.Failure
import com.bitchat.domain.lora.model.LoRaProtocolType

sealed class LoRaFailure : Failure.FeatureFailure() {
    /** This app has no LoRa stack. */
    data object NoRadio : LoRaFailure()

    /** This app does not ship the requested protocol. */
    data object NotRegistered : LoRaFailure()

    /** The radio of [protocol], the active one, is configured on the device itself. */
    data class KeepsOwnRadioSettings(val protocol: LoRaProtocolType) : LoRaFailure()

    /** The radio did not start with the new protocol or settings. */
    data object NotApplied : LoRaFailure()
}
