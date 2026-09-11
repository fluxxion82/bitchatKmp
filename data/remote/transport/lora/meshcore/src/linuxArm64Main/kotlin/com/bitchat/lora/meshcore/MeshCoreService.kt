package com.bitchat.lora.meshcore

import com.bitchat.lora.service.LoRaDaemon
import com.bitchat.lora.service.LinuxLoRaServices

/** Uses the same verified ownership policy as every other embedded LoRa protocol. */
object MeshCoreService {
    private val daemon = LoRaDaemon.MESHCORE
    fun start(): Boolean = LinuxLoRaServices.report(LinuxLoRaServices.controller.start(daemon))
    fun stop(): Boolean = LinuxLoRaServices.report(LinuxLoRaServices.controller.stop(daemon))
    fun isInstalled(): Boolean = LinuxLoRaServices.backend.snapshot(daemon).let { it.known && it.installed }
    fun isRunning(): Boolean = LinuxLoRaServices.backend.snapshot(daemon).let { it.known && it.state == "active" }
    fun isListening(): Boolean = LinuxLoRaServices.backend.isListening(daemon)
}
