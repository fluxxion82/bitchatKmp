package com.bitchat.lora.bitchat.radio

import com.bitchat.lora.service.LinuxLoRaServices

internal object LoRaServiceManager {
    fun ensureNoConflictingServices(): Boolean =
        LinuxLoRaServices.report(LinuxLoRaServices.controller.prepareDirectRadio())
}
