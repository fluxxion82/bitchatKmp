package com.bitchat.repo.initialization

import com.bitchat.domain.app.model.ActiveState
import com.bitchat.domain.app.model.UserState
import com.bitchat.domain.initialization.AppInitializer
import com.bitchat.domain.location.model.Channel
import com.bitchat.domain.user.eventbus.UserEventBus
import com.bitchat.domain.user.model.UserEvent
import com.bitchat.domain.user.repository.UserRepository
import org.koin.dsl.bind
import org.koin.dsl.module

/**
 * Headless-app initializer that auto-activates the user state.
 *
 * On mobile platforms, users go through an onboarding flow that grants permissions
 * and sets UserState.Active. Headless apps have no such flow, so they need to
 * auto-activate to enable Bluetooth mesh networking.
 *
 * [com.bitchat.domain.initialization.InitializeApplication] launches all
 * initializers concurrently, so this does not run before the Bluetooth initializer.
 * Bluetooth startup instead relies on ChatRepo reacting to the state and
 * [UserEvent.LoginChanged] (ChatRepo.kt:173).
 */
class HeadlessUserStateInitializer(
    private val userRepository: UserRepository,
    private val userEventBus: UserEventBus,
) : AppInitializer {

    override suspend fun initialize() {
        val currentState = userRepository.getUserState()

        if (currentState == null) {
            println("HeadlessUserStateInitializer: No user state, auto-activating...")

            // Set to Active state with Mesh channel
            val activeState = UserState.Active(ActiveState.Chat(Channel.Mesh))
            userRepository.setUserState(activeState)

            // Get or create app user for the event
            val appUser = userRepository.getAppUser()

            // Emit LoginChanged to trigger ChatRepo's Bluetooth service start
            userEventBus.update(UserEvent.LoginChanged(appUser))

            println("HeadlessUserStateInitializer: User state activated -> $activeState, user -> $appUser")
        } else {
            println("HeadlessUserStateInitializer: User state already set -> $currentState")
        }
    }
}

val headlessUserStateModule = module {
    single { HeadlessUserStateInitializer(get(), get()) } bind AppInitializer::class
}
