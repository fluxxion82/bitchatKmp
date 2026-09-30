package com.bitchat.domain.chat

import com.bitchat.domain.chat.repository.ChatRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class ClearSelectedPrivatePeerTest {
    private val chatRepository = mockk<ChatRepository>(relaxed = true)
    private val useCase = ClearSelectedPrivatePeer(chatRepository)

    @Test
    fun `clears the selected private peer when it is the one asked about`() = runTest {
        coEvery { chatRepository.getSelectedPrivatePeer() } returns "b0b"
        useCase("b0b")
        coVerify(exactly = 1) { chatRepository.setSelectedPrivatePeer(null) }
    }

    @Test
    fun `leaves another selected private peer alone`() = runTest {
        coEvery { chatRepository.getSelectedPrivatePeer() } returns "a11ce"
        useCase("b0b")
        coVerify(exactly = 0) { chatRepository.setSelectedPrivatePeer(any()) }
    }
}
