package com.bitchat.domain.chat

import com.bitchat.domain.base.model.Outcome
import com.bitchat.domain.chat.eventbus.ChatEventBus
import com.bitchat.domain.chat.model.ChatEvent
import com.bitchat.domain.chat.model.failure.ChannelFailure
import com.bitchat.domain.chat.repository.ChatRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SetChannelPasswordTest {

    private val chatRepository = mockk<ChatRepository>(relaxed = true)
    private val chatEventBus = mockk<ChatEventBus>(relaxed = true)
    private val setChannelPassword = SetChannelPassword(chatRepository, chatEventBus)

    private fun params(newPassword: String, currentPassword: String? = null) =
        SetChannelPassword.Params(CHANNEL, currentPassword, newPassword)

    private fun noCommitment(owner: Boolean) {
        coEvery { chatRepository.getChannelKeyCommitment(CHANNEL) } returns null
        coEvery { chatRepository.isChannelOwner(CHANNEL) } returns owner
    }

    private fun committedTo(password: String, owner: Boolean) {
        val key = byteArrayOf(1)
        coEvery { chatRepository.getChannelKeyCommitment(CHANNEL) } returns COMMITMENT
        coEvery { chatRepository.deriveChannelKey(CHANNEL, password) } returns key
        coEvery { chatRepository.calculateKeyCommitment(key) } returns COMMITMENT
        coEvery { chatRepository.isChannelOwner(CHANNEL) } returns owner
    }

    @Test
    fun `a first password from someone who is not the creator fails as OnlyCreatorCanSetPassword`() = runTest {
        noCommitment(owner = false)

        assertEquals(Outcome.Error(ChannelFailure.OnlyCreatorCanSetPassword), setChannelPassword(params("new")))

        coVerify(exactly = 0) { chatRepository.setChannelPassword(any(), any()) }
        coVerify(exactly = 0) { chatEventBus.update(any()) }
    }

    @Test
    fun `the creator sets the first password and the ownership is published`() = runTest {
        noCommitment(owner = true)

        assertEquals(Outcome.Success(Unit), setChannelPassword(params("new")))

        coVerifyOrder {
            chatRepository.setChannelPassword(CHANNEL, "new")
            chatEventBus.update(ChatEvent.ChannelOwnershipVerified(CHANNEL))
        }
    }

    @Test
    fun `a first write that throws carries its message or none`() = runTest {
        noCommitment(owner = true)

        coEvery { chatRepository.setChannelPassword(CHANNEL, "new") } throws IllegalStateException("disk full")
        assertEquals(Outcome.Error(ChannelFailure.PasswordNotSaved("disk full")), setChannelPassword(params("new")))

        coEvery { chatRepository.setChannelPassword(CHANNEL, "new") } throws IllegalStateException()
        assertEquals(Outcome.Error(ChannelFailure.PasswordNotSaved(null)), setChannelPassword(params("new")))

        coVerify(exactly = 0) { chatEventBus.update(any()) }
    }

    @Test
    fun `without a current password the password given proves ownership`() = runTest {
        coEvery { chatRepository.getChannelKeyCommitment(CHANNEL) } returns COMMITMENT
        coEvery { chatRepository.verifyPasswordOwnership(CHANNEL, "secret") } returns true

        assertEquals(Outcome.Success(Unit), setChannelPassword(params("secret")))

        coVerify(exactly = 0) { chatRepository.setChannelPassword(any(), any()) }
    }

    @Test
    fun `without a current password a password that does not prove ownership fails as WrongPassword`() = runTest {
        coEvery { chatRepository.getChannelKeyCommitment(CHANNEL) } returns COMMITMENT
        coEvery { chatRepository.verifyPasswordOwnership(CHANNEL, "guess") } returns false

        assertEquals(Outcome.Error(ChannelFailure.WrongPassword), setChannelPassword(params("guess")))

        coVerify(exactly = 0) { chatRepository.setChannelPassword(any(), any()) }
    }

    @Test
    fun `a current password that does not match the commitment fails as WrongPassword`() = runTest {
        committedTo("current", owner = true)
        val other = byteArrayOf(2)
        coEvery { chatRepository.deriveChannelKey(CHANNEL, "guess") } returns other
        coEvery { chatRepository.calculateKeyCommitment(other) } returns "other"

        assertEquals(Outcome.Error(ChannelFailure.WrongPassword), setChannelPassword(params("new", currentPassword = "guess")))

        coVerify(exactly = 0) { chatRepository.setChannelPassword(any(), any()) }
        coVerify(exactly = 0) { chatRepository.verifyPasswordOwnership(any(), any()) }
        coVerify(exactly = 0) { chatEventBus.update(any()) }
    }

    @Test
    fun `the owner changes the password with the current one`() = runTest {
        committedTo("current", owner = true)

        assertEquals(Outcome.Success(Unit), setChannelPassword(params("new", currentPassword = "current")))

        coVerifyOrder {
            chatRepository.setChannelPassword(CHANNEL, "new")
            chatEventBus.update(ChatEvent.ChannelOwnershipVerified(CHANNEL))
        }
        coVerify(exactly = 0) { chatRepository.verifyPasswordOwnership(any(), any()) }
    }

    @Test
    fun `someone who is not the owner claims ownership with the current password before the change`() = runTest {
        committedTo("current", owner = false)
        coEvery { chatRepository.verifyPasswordOwnership(CHANNEL, "current") } returns true

        assertEquals(Outcome.Success(Unit), setChannelPassword(params("new", currentPassword = "current")))

        coVerifyOrder {
            chatRepository.verifyPasswordOwnership(CHANNEL, "current")
            chatRepository.setChannelPassword(CHANNEL, "new")
        }
    }

    @Test
    fun `a change whose write throws carries its message`() = runTest {
        committedTo("current", owner = true)
        coEvery { chatRepository.setChannelPassword(CHANNEL, "new") } throws IllegalStateException("disk full")

        assertEquals(
            Outcome.Error(ChannelFailure.PasswordNotSaved("disk full")),
            setChannelPassword(params("new", currentPassword = "current")),
        )
    }

    @Test
    fun `a cancelled write is not reported as a failed one`() = runTest {
        noCommitment(owner = true)
        coEvery { chatRepository.setChannelPassword(CHANNEL, "new") } throws CancellationException("left the screen")
        assertFailsWith<CancellationException> { setChannelPassword(params("new")) }

        committedTo("current", owner = true)
        assertFailsWith<CancellationException> { setChannelPassword(params("new", currentPassword = "current")) }
    }

    private companion object {
        const val CHANNEL = "#test"
        const val COMMITMENT = "commitment"
    }
}
