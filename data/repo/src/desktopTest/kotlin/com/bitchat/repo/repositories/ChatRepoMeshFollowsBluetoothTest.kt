package com.bitchat.repo.repositories

import com.bitchat.bluetooth.service.BluetoothMeshService
import com.bitchat.domain.app.model.ActiveState
import com.bitchat.domain.app.model.UserState
import com.bitchat.domain.app.repository.AppRepository
import com.bitchat.domain.base.CoroutinesContextFacade
import com.bitchat.domain.connectivity.eventbus.ConnectionEventBus
import com.bitchat.domain.connectivity.model.BluetoothConnectionEvent
import com.bitchat.domain.user.eventbus.InMemoryUserEventBus
import com.bitchat.domain.user.model.AppUser
import com.bitchat.domain.user.model.UserEvent
import com.bitchat.domain.user.repository.UserRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class ChatRepoMeshFollowsBluetoothTest {

    @Test
    fun bluetoothStopsAndStartsTheMeshAfterAUserEventThatIsNotAStateChange() = runTest {
        for (event in listOf(
            UserEvent.NicknameUpdated,
            UserEvent.ProfileUpdated,
            UserEvent.FavoriteStatusChanged("peer"),
            UserEvent.FavoritesCleared,
        )) {
            withRepo(UserState.Active(ActiveState.Settings)) {
                assertEquals(listOf("start"), calls, "at start, before $event")

                userEventBus.update(event)
                assertEquals(listOf("start"), calls, "$event alone")

                bluetooth.value = BluetoothConnectionEvent.DISCONNECTED
                assertEquals(listOf("start", "stop"), calls, "Bluetooth off after $event")

                bluetooth.value = BluetoothConnectionEvent.CONNECTED
                assertEquals(listOf("start", "stop", "start"), calls, "Bluetooth on again after $event")
            }
        }
    }

    // The headless boards start the mesh with LoginChanged.
    @Test
    fun aStateChangeOrLoginDecidesAgain() = runTest {
        for (event in listOf(UserEvent.StateChanged, UserEvent.LoginChanged(AppUser.ActiveAnonymous("Alice")))) {
            withRepo(userState = null) {
                assertEquals(listOf("stop"), calls, "at start, before $event")

                userState = UserState.Active(ActiveState.Settings)
                userEventBus.update(event)
                assertEquals(listOf("stop", "start"), calls, "after $event")
            }
        }
    }

    private class Rig(val userEventBus: InMemoryUserEventBus, var userState: UserState?) {
        val calls = mutableListOf<String>()
        val bluetooth = MutableStateFlow(BluetoothConnectionEvent.CONNECTED)
    }

    private suspend fun TestScope.withRepo(userState: UserState?, block: suspend Rig.() -> Unit) {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val rig = Rig(InMemoryUserEventBus(contextFacade(dispatcher)), userState)
        try {
            chatRepo(
                scope, dispatcher, mutableListOf(),
                mesh = mockk<BluetoothMeshService>(relaxed = true).also {
                    every { it.startServices() } answers { rig.calls += "start" }
                    every { it.stopServices() } answers { rig.calls += "stop" }
                },
                userEventBus = rig.userEventBus,
                userRepository = mockk<UserRepository>(relaxed = true).also {
                    coEvery { it.getUserState() } answers { rig.userState }
                },
                connectEventBus = mockk<ConnectionEventBus>(relaxed = true).also {
                    coEvery { it.getBluetoothConnectionEvent() } returns rig.bluetooth
                },
                appRepository = mockk<AppRepository>(relaxed = true).also {
                    coEvery { it.hasRequiredPermissions() } returns true
                },
            )
            rig.block()
        } finally {
            scope.cancel()
        }
    }

    private fun contextFacade(dispatcher: CoroutineContext) = object : CoroutinesContextFacade {
        override val io: CoroutineContext = dispatcher
        override val main: CoroutineContext = dispatcher
        override val default: CoroutineContext = dispatcher
        override val unconfined: CoroutineContext = dispatcher
    }
}
