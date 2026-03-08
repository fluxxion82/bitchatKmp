package com.bitchat.repo.di

import com.bitchat.domain.app.model.UserState
import com.bitchat.domain.initialization.AppInitializer
import com.bitchat.domain.lora.model.LoRaRegion
import com.bitchat.domain.lora.model.LoRaTxPower
import com.bitchat.domain.user.eventbus.UserEventBus
import com.bitchat.domain.user.model.UserEvent
import com.bitchat.domain.user.repository.UserRepository
import com.bitchat.local.prefs.LoRaPreferences
import com.bitchat.lora.LoRaProtocol
import com.bitchat.lora.radio.LoRaConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * App initializer for LoRa transport.
 *
 * Automatically starts LoRa radio on app launch when:
 * - LoRa is enabled in settings
 * - User is in Active state
 * - LoRa transport is available (hardware detected)
 *
 * Uses settings from LoRaPreferences for region and TX power.
 *
 * NOTE: This initializer is non-blocking - it launches LoRa setup in the background
 * and returns immediately to avoid blocking app startup.
 */
class LoRaAppInitializer(
    private val loraTransport: LoRaProtocol?,
    private val userRepository: UserRepository,
    private val loraPreferences: LoRaPreferences?,
    private val userEventBus: UserEventBus,
) : AppInitializer {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override suspend fun initialize() {
        if (loraTransport == null) {
            println("LoRaAppInitializer: LoRa transport not available on this platform")
            return
        }

        // Check if LoRa is enabled in settings
        if (loraPreferences?.isLoRaEnabled() == false) {
            println("LoRaAppInitializer: LoRa is disabled in settings")
            return
        }

        // Initial attempt (might fail if user not Active yet)
        scope.launch {
            tryStartLoRa()
        }

        // Subscribe to user state changes for retry
        // This ensures LoRa starts when user becomes Active after app startup
        scope.launch {
            userEventBus.events().collect { event ->
                if (event is UserEvent.StateChanged) {
                    println("LoRaAppInitializer: User state changed, attempting LoRa start...")
                    tryStartLoRa()
                }
            }
        }

        // Return immediately - LoRa will initialize in background
        println("LoRaAppInitializer: Scheduled LoRa initialization (non-blocking)")
    }

    private suspend fun tryStartLoRa() {
        if (loraTransport?.isReady == true) {
            println("LoRaAppInitializer: LoRa already ready, skipping")
            return
        }

        try {
            val userState = userRepository.getUserState()

            if (userState is UserState.Active) {
                val config = buildLoRaConfig()
                println("LoRaAppInitializer: Starting LoRa transport in background (region=${loraPreferences?.getLoRaRegion()}, txPower=${loraPreferences?.getTxPower()})...")
                val started = loraTransport?.start(config) ?: false
                if (started) {
                    println("LoRaAppInitializer: LoRa transport started successfully")
                } else {
                    println("LoRaAppInitializer: LoRa transport failed to start (no hardware or config error)")
                }
            } else {
                println("LoRaAppInitializer: User not Active ($userState), will retry on state change")
            }
        } catch (e: Exception) {
            println("LoRaAppInitializer: Error starting LoRa: ${e.message}")
        }
    }

    private fun buildLoRaConfig(): LoRaConfig {
        val prefs = loraPreferences ?: return LoRaConfig.US_915

        val region = prefs.getLoRaRegion()

        // E22 modules on RangePi operate on fixed 1 MHz channels.
        // Frequency scan confirmed E22 is at 868.125 MHz (-85dBm strongest signal)
        val frequency = when (region) {
            LoRaRegion.US_915 -> 915_125_000L
            LoRaRegion.EU_868 -> 868_125_000L  // Confirmed by frequency scan
            LoRaRegion.AU_915 -> 915_125_000L
            LoRaRegion.AS_923 -> 923_125_000L
        }

        val txPower = when (prefs.getTxPower()) {
            LoRaTxPower.LOW -> 10
            LoRaTxPower.MEDIUM -> 17
            LoRaTxPower.HIGH -> 20
        }

        // RangePi E22 modules may use 0x12 (public) or 0x34 (private) LoRa sync word.
        val syncWord = when (region) {
            LoRaRegion.EU_868 -> 0x12  // Standard public LoRa sync word
            else -> 0xBC
        }

        // Match E22 default "2.4k" air-rate profile (SF9) for interop with RangePi/embedded.
        // E22 2.4k air rate uses approximately SF9. Using SF10 won't communicate with SF9 devices.
        val spreadingFactor = when (region) {
            LoRaRegion.EU_868 -> 9  // Changed from 10 to match E22 module
            else -> 9
        }

        return LoRaConfig(
            frequency = frequency,
            txPower = txPower,
            syncWord = syncWord,
            spreadingFactor = spreadingFactor
        )
    }
}
