package com.bitchat.embedded.di

import com.bitchat.domain.app.model.ActiveState
import com.bitchat.domain.app.model.UserState
import com.bitchat.domain.initialization.AppInitializer
import com.bitchat.domain.location.model.Channel
import com.bitchat.domain.user.eventbus.UserEventBus
import com.bitchat.domain.user.model.UserEvent
import com.bitchat.domain.user.repository.UserRepository

/**
 * Embedded-specific initializer that auto-activates the user state.
 *
 * On mobile platforms, users go through an onboarding flow that grants permissions
 * and sets UserState.Active. On embedded Linux, there's no such flow, so we need
 * to auto-activate to enable Bluetooth mesh networking.
 *
 * This initializer should run BEFORE BluetoothMeshAppInitializer.
 */
class EmbeddedUserStateInitializer(
    private val userRepository: UserRepository,
    private val userEventBus: UserEventBus,
) : AppInitializer {

    override suspend fun initialize() {
        val currentState = userRepository.getUserState()

        if (currentState == null) {
            println("EmbeddedUserStateInitializer: No user state, auto-activating for embedded...")

            // Set to Active state with Mesh channel
            val activeState = UserState.Active(ActiveState.Chat(Channel.Mesh))
            userRepository.setUserState(activeState)

            // Get or create app user for the event
            val appUser = userRepository.getAppUser()

            // Emit LoginChanged to trigger ChatRepo's Bluetooth service start
            userEventBus.update(UserEvent.LoginChanged(appUser))

            println("EmbeddedUserStateInitializer: User state activated -> $activeState, user -> $appUser")
        } else {
            println("EmbeddedUserStateInitializer: User state already set -> $currentState")
        }
    }
}
