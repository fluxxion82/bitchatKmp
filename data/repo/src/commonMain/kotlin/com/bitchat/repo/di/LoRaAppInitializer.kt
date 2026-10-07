package com.bitchat.repo.di

import com.bitchat.bluetooth.service.BluetoothMeshService
import com.bitchat.domain.app.model.UserState
import com.bitchat.domain.initialization.AppInitializer
import com.bitchat.domain.user.eventbus.UserEventBus
import com.bitchat.domain.user.model.AppUser
import com.bitchat.domain.user.model.UserEvent
import com.bitchat.domain.user.repository.UserRepository
import com.bitchat.local.prefs.LoRaPreferences
import com.bitchat.lora.LoRaProtocol
import com.bitchat.lora.radio.LoRaConfig
import com.bitchat.repo.lora.toLoRaConfiguration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * App initializer for LoRa transport.
 *
 * Attempts LoRa bootstrap once per app lifetime, when:
 * - LoRa is enabled in settings
 * - User is in Active state
 * - A LoRa protocol adapter is available (hardware identification happens during startup)
 *
 * Uses settings from LoRaPreferences for region, TX power and bandwidth.
 *
 * NOTE: This initializer is non-blocking - it launches LoRa setup in the background
 * and returns immediately to avoid blocking app startup.
 */
class LoRaAppInitializer(
    private val loraTransport: LoRaProtocol?,
    private val userRepository: UserRepository,
    private val loraPreferences: LoRaPreferences?,
    private val userEventBus: UserEventBus,
    private val bluetoothMeshService: BluetoothMeshService,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
) : AppInitializer {


    private val initializationMutex = Mutex()
    private val attemptMutex = Mutex()
    private var initialized = false
    private var bootstrapAttempted = false

    override suspend fun initialize() = initializationMutex.withLock {
        if (initialized) return@withLock
        initialized = true
        if (loraTransport == null) {
            println("LoRaAppInitializer: LoRa transport not available on this platform")
            return@withLock
        }

        // Subscribe before the initial attempt so an Active transition cannot be missed.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            userEventBus.events().collect { event ->
                when (event) {
                    UserEvent.StateChanged -> tryStartLoRa()
                    UserEvent.NicknameUpdated,
                    UserEvent.ProfileUpdated,
                    is UserEvent.LoginChanged -> syncLocalIdentity()
                    is UserEvent.FavoriteStatusChanged -> Unit
                }
            }
        }
        scope.launch { tryStartLoRa() }
        println("LoRaAppInitializer: Scheduled one LoRa bootstrap attempt when the user is Active")
    }

    private suspend fun tryStartLoRa() = attemptMutex.withLock {
        if (bootstrapAttempted) return@withLock
        try {
            val userState = userRepository.getUserState()
            if (userState !is UserState.Active) return@withLock
            // A preference can change while waiting for permission/onboarding state.
            if (loraPreferences?.isLoRaEnabled() == false) return@withLock

            // Claim the one bootstrap attempt before invoking the transport. A failed
            // radio must not restart on ordinary Settings/Chat/Locations navigation.
            // Explicit user selections/retries call the manager independently.
            bootstrapAttempted = true
            syncLocalIdentity()
            if (loraTransport?.isReady == true) return@withLock
            val config = loraPreferences?.toLoRaConfiguration() ?: LoRaConfig.US_915
            println("LoRaAppInitializer: Starting saved LoRa selection (region=${loraPreferences?.getLoRaRegion()}, txPower=${loraPreferences?.getTxPower()}, bandwidth=${loraPreferences?.getBandwidth()})")
            val started = loraTransport?.start(config) ?: false
            if (started) {
                println("LoRaAppInitializer: LoRa transport started successfully")
            } else {
                println("LoRaAppInitializer: LoRa bootstrap failed; use Settings to retry")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            println("LoRaAppInitializer: Error starting LoRa: ${error.message}")
        }
    }

    /** Uses the persistent BLE identity, which is also the BitChat protocol's 8-byte sender ID. */
    private suspend fun syncLocalIdentity() {
        val transport = loraTransport ?: return
        transport.deviceId = bluetoothMeshService.myPeerID
        transport.nickname = when (val user = userRepository.getAppUser()) {
            is AppUser.ActiveAnonymous -> user.name
            AppUser.Anonymous -> "anon"
        }
    }

}
