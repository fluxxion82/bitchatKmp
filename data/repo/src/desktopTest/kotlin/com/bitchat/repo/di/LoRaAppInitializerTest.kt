package com.bitchat.repo.di

import com.bitchat.bluetooth.service.BluetoothMeshService
import com.bitchat.domain.app.model.ActiveState
import com.bitchat.domain.app.model.UserState
import com.bitchat.domain.location.model.Channel
import com.bitchat.domain.lora.model.LoRaRegion
import com.bitchat.domain.lora.model.LoRaTxPower
import com.bitchat.domain.user.eventbus.UserEventBus
import com.bitchat.domain.user.model.AppUser
import com.bitchat.domain.user.model.UserEvent
import com.bitchat.domain.user.repository.UserRepository
import com.bitchat.local.prefs.LoRaPreferences
import com.bitchat.lora.LoRaProtocol
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class LoRaAppInitializerTest {
    private val transport = mockk<LoRaProtocol>(relaxed = true)
    private val users = mockk<UserRepository>()
    private val preferences = mockk<LoRaPreferences>()
    private val mesh = mockk<BluetoothMeshService>()
    private val events = MutableSharedFlow<UserEvent>(extraBufferCapacity = 8)
    private val bus = object : UserEventBus {
        override fun events() = events
        override suspend fun update(event: UserEvent) { events.emit(event) }
    }
    private var enabled = true
    private var state: UserState = UserState.Active(ActiveState.Settings)

    init {
        every { transport.isReady } returns false
        coEvery { transport.start(any()) } returns false
        coEvery { users.getUserState() } answers { state }
        coEvery { users.getAppUser() } returns AppUser.ActiveAnonymous("anon")
        every { mesh.myPeerID } returns "0123456789abcdef"
        every { preferences.isLoRaEnabled() } answers { enabled }
        every { preferences.getLoRaRegion() } returns LoRaRegion.US_915
        every { preferences.getTxPower() } returns LoRaTxPower.LOW
    }

    @Test
    fun navigationDoesNotRetryFailedBootstrap() = runTest {
        val initializer = LoRaAppInitializer(transport, users, preferences, bus, mesh, backgroundScope)
        initializer.initialize()
        runCurrent()
        state = UserState.Active(ActiveState.Chat(Channel.Mesh))
        events.emit(UserEvent.StateChanged)
        runCurrent()
        state = UserState.Active(ActiveState.Settings)
        events.emit(UserEvent.StateChanged)
        runCurrent()
        coVerify(exactly = 1) { transport.start(any()) }
    }

    @Test
    fun disablingAfterInitializationPreventsDeferredBootstrap() = runTest {
        state = UserState.PermissionsRequired
        val initializer = LoRaAppInitializer(transport, users, preferences, bus, mesh, backgroundScope)
        initializer.initialize()
        runCurrent()
        enabled = false
        state = UserState.Active(ActiveState.Settings)
        events.emit(UserEvent.StateChanged)
        runCurrent()
        coVerify(exactly = 0) { transport.start(any()) }
    }

    @Test
    fun concurrentStartupAndEventsShareOneAttempt() = runTest {
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        coEvery { transport.start(any()) } coAnswers { entered.complete(Unit); finish.await(); false }
        val initializer = LoRaAppInitializer(transport, users, preferences, bus, mesh, backgroundScope)
        initializer.initialize()
        runCurrent()
        entered.await()
        events.emit(UserEvent.StateChanged)
        initializer.initialize()
        runCurrent()
        coVerify(exactly = 1) { transport.start(any()) }
        finish.complete(Unit)
        runCurrent()
        coVerify(exactly = 1) { transport.start(any()) }
    }

    @Test
    fun firstActiveTransitionBootstrapsAfterPermissions() = runTest {
        state = UserState.PermissionsRequired
        val initializer = LoRaAppInitializer(transport, users, preferences, bus, mesh, backgroundScope)
        initializer.initialize()
        runCurrent()
        coVerify(exactly = 0) { transport.start(any()) }
        state = UserState.Active(ActiveState.Settings)
        events.emit(UserEvent.StateChanged)
        runCurrent()
        coVerify(exactly = 1) { transport.start(any()) }
    }

    @Test
    fun bootstrapSuppliesPersistentMeshIdentityAndNicknameBeforeStarting() = runTest {
        coEvery { users.getAppUser() } returns AppUser.ActiveAnonymous("Orange Pi")
        var assignedDeviceId = ""
        var assignedNickname = ""
        every { transport.deviceId = any() } answers { assignedDeviceId = firstArg() }
        every { transport.nickname = any() } answers { assignedNickname = firstArg() }
        coEvery { transport.start(any()) } coAnswers {
            assertEquals("0123456789abcdef", assignedDeviceId)
            assertEquals("Orange Pi", assignedNickname)
            false
        }

        LoRaAppInitializer(
            loraTransport = transport,
            userRepository = users,
            loraPreferences = preferences,
            userEventBus = bus,
            bluetoothMeshService = mesh,
            scope = backgroundScope,
        ).initialize()

        runCurrent()
        coVerify(exactly = 1) { transport.start(any()) }
    }
}
