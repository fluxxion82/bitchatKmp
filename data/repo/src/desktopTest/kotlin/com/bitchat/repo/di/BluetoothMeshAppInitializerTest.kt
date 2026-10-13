package com.bitchat.repo.di

import com.bitchat.bluetooth.service.BluetoothMeshService
import com.bitchat.domain.app.model.ActiveState
import com.bitchat.domain.app.model.UserState
import com.bitchat.domain.app.repository.AppRepository
import com.bitchat.domain.chat.repository.ChatRepository
import com.bitchat.domain.connectivity.repository.ConnectivityRepository
import com.bitchat.domain.location.model.Channel
import com.bitchat.domain.user.repository.UserRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals

class BluetoothMeshAppInitializerTest {

    // Until the chat repository exists the mesh has no delegate: it announces a placeholder name
    // and has nowhere to hand what it receives.
    @Test
    fun `the chat repository is built before the mesh is started`() = runTest {
        val order = mutableListOf<String>()
        val mesh = mockk<BluetoothMeshService>(relaxed = true) {
            every { startServices() } answers { order += "mesh started" }
        }
        val application = koinApplication(createEagerInstances = false) {
            allowOverride(true)
            modules(
                commonRepoModule,
                module {
                    single { mesh }
                    single<ChatRepository> {
                        order += "chat repository built"
                        mockk<ChatRepository>(relaxed = true)
                    }
                    single<UserRepository> {
                        mockk { coEvery { getUserState() } returns UserState.Active(ActiveState.Chat(Channel.Mesh)) }
                    }
                    single<AppRepository> { mockk { coEvery { hasRequiredPermissions() } returns true } }
                    single<ConnectivityRepository> { mockk { coEvery { isBluetoothEnabled() } returns true } }
                },
            )
        }

        try {
            application.koin.get<BluetoothMeshAppInitializer>().initialize()

            assertEquals(listOf("chat repository built", "mesh started"), order)
        } finally {
            application.close()
        }
    }
}
