package com.bitchat.repo.repositories

import com.bitchat.bluetooth.service.BluetoothMeshService
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatRepoMeshDelegateTest {

    @Test
    fun aNewRepositoryIsTheMeshDelegate() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val mesh = mockk<BluetoothMeshService>(relaxed = true)
        try {
            val repo = chatRepo(scope, dispatcher, mutableListOf(), mesh = mesh)

            verify { mesh.delegate = repo }
        } finally {
            scope.cancel()
        }
    }
}
