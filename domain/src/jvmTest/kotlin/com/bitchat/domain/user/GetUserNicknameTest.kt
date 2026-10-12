package com.bitchat.domain.user

import com.bitchat.domain.user.model.AppUser
import com.bitchat.domain.user.repository.UserRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class GetUserNicknameTest {
    private val userRepository = mockk<UserRepository>()
    private val getUserNickname = GetUserNickname(userRepository)

    @Test
    fun `returns the name of a named user`() = runTest {
        coEvery { userRepository.getAppUser() } returns AppUser.ActiveAnonymous("Alice")

        assertEquals("Alice", getUserNickname(Unit))
    }

    @Test
    fun `returns anon for a user without a name`() = runTest {
        coEvery { userRepository.getAppUser() } returns AppUser.Anonymous

        assertEquals("anon", getUserNickname(Unit))
    }
}
