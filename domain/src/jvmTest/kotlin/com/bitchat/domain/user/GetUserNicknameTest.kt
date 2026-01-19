package com.bitchat.domain.user

import app.cash.turbine.test
import com.bitchat.domain.base.defaultContextFacade
import com.bitchat.domain.user.eventbus.InMemoryUserEventBus
import com.bitchat.domain.user.model.AppUser
import com.bitchat.domain.user.model.UserEvent
import com.bitchat.domain.user.repository.UserRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class GetUserNicknameTest {
    private val userRepository = mockk<UserRepository>()
    private val userEventBus = InMemoryUserEventBus(defaultContextFacade)
    private val useCase = GetUserNickname(userRepository, userEventBus)

    @Test
    fun `emits nickname for ActiveAnonymous user`() = runTest {
        coEvery { userRepository.getAppUser() } returns AppUser.ActiveAnonymous("Alice")

        useCase(Unit).test {
            assertEquals("Alice", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `emits anon for Anonymous user`() = runTest {
        coEvery { userRepository.getAppUser() } returns AppUser.Anonymous

        useCase(Unit).test {
            assertEquals("anon", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `emits updated nickname on NicknameUpdated event`() = runTest {
        coEvery { userRepository.getAppUser() } returnsMany listOf(
            AppUser.ActiveAnonymous("Alice"),
            AppUser.ActiveAnonymous("Bob")
        )

        useCase(Unit).test {
            assertEquals("Alice", awaitItem())

            userEventBus.update(UserEvent.NicknameUpdated)
            assertEquals("Bob", awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `emits updated nickname on LoginChanged event`() = runTest {
        val user1 = AppUser.ActiveAnonymous("Alice")
        val user2 = AppUser.ActiveAnonymous("Charlie")

        coEvery { userRepository.getAppUser() } returnsMany listOf(user1, user2)

        useCase(Unit).test {
            assertEquals("Alice", awaitItem())

            userEventBus.update(UserEvent.LoginChanged(user2))
            assertEquals("Charlie", awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }
}
