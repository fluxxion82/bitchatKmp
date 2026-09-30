package com.bitchat.repo.initialization

import com.bitchat.domain.app.model.ActiveState
import com.bitchat.domain.app.model.UserState
import com.bitchat.domain.location.model.Channel
import com.bitchat.domain.user.eventbus.UserEventBus
import com.bitchat.domain.user.model.AppUser
import com.bitchat.domain.user.model.UserEvent
import com.bitchat.domain.user.repository.UserRepository
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class HeadlessUserStateInitializerTest {
    private val users = mockk<UserRepository>()
    private val events = mockk<UserEventBus>(relaxed = true)
    private val appUser = AppUser.ActiveAnonymous("desktop")

    @Test
    fun nullStateActivatesMeshAndEmitsLoginChanged() = runTest {
        coEvery { users.getUserState() } returns null
        coEvery { users.getAppUser() } returns appUser
        coJustRun { users.setUserState(any()) }

        HeadlessUserStateInitializer(users, events).initialize()

        coVerify(exactly = 1) {
            users.setUserState(UserState.Active(ActiveState.Chat(Channel.Mesh)))
        }
        coVerify(exactly = 1) { events.update(UserEvent.LoginChanged(appUser)) }
    }

    @Test
    fun existingStateDoesNotWriteOrEmitAnEvent() = runTest {
        val existingState = UserState.Active(ActiveState.Settings)
        coEvery { users.getUserState() } returns existingState

        HeadlessUserStateInitializer(users, events).initialize()

        coVerify(exactly = 0) { users.setUserState(any()) }
        coVerify(exactly = 0) { events.update(any()) }
        coVerify(exactly = 0) { users.getAppUser() }
    }
}
