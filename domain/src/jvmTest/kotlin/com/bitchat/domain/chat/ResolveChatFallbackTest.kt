package com.bitchat.domain.chat

import com.bitchat.domain.app.model.ActiveState
import com.bitchat.domain.app.model.UserState
import com.bitchat.domain.chat.repository.ChatRepository
import com.bitchat.domain.location.model.Channel
import com.bitchat.domain.location.model.GeohashChannelLevel
import com.bitchat.domain.user.repository.UserRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ResolveChatFallbackTest {
    private var previous: Channel? = null
    private var current: Channel = Channel.Mesh
    private var joined = listOf("#a", "#test")

    private val resolve = ResolveChatFallback(
        userRepository = mockk<UserRepository> {
            coEvery { getUserState() } answers { UserState.Active(ActiveState.Chat(current, previousChannel = previous)) }
        },
        chatRepository = mockk<ChatRepository> { coEvery { getJoinedChannelsList() } answers { joined } },
    )

    @Test fun theChatBeingLeftIsNeverWhereItGoes() = runTest {
        previous = Channel.NamedChannel("#test")
        // However it is spelled: "/leave test" leaves the same channel as "/leave #Test".
        assertEquals(Channel.Mesh, resolve(Channel.NamedChannel("test")))
        assertEquals(Channel.Mesh, resolve(Channel.NamedChannel("#TEST")))
    }

    @Test fun aChannelTheUserHasLeftIsNotReturnedTo() = runTest {
        previous = Channel.NamedChannel("#b") // "/j a", "/j b", "/leave" (to #a), "/leave"
        assertEquals(Channel.Mesh, resolve(Channel.NamedChannel("#a")))
    }

    @Test fun aChannelStillJoinedIsWhereItGoes() = runTest {
        previous = Channel.NamedChannel("#a")
        assertEquals(Channel.NamedChannel("#a"), resolve(Channel.NamedChannel("#test")))
    }

    @Test fun backOutOfAChatThatIsAlsoTheOneItCameFromGoesToTheMesh() = runTest {
        // Back names no channel, so the chat in view is the one being left. A saved state whose
        // current and previous chats are the same one must not answer with that same chat.
        current = Channel.NamedChannel("#test")
        previous = Channel.NamedChannel("#test")
        assertEquals(Channel.Mesh, resolve(null))
    }

    @Test fun leavingAnotherChannelStillGoesBackToTheOneInView() = runTest {
        // "/leave #a" while sitting in #test names #a, so #test is not guarded against.
        current = Channel.NamedChannel("#test")
        previous = Channel.NamedChannel("#test")
        assertEquals(Channel.NamedChannel("#test"), resolve(Channel.NamedChannel("#a")))
    }

    @Test fun otherChatsComeBackAsTheyAre() = runTest {
        val city = Channel.Location(GeohashChannelLevel.CITY, "9q8yy")
        previous = city
        assertEquals(city, resolve(Channel.NamedChannel("#test")))
        previous = null
        assertEquals(Channel.Mesh, resolve(Channel.NamedChannel("#test")))
        previous = Channel.MeshDM("b0b")
        assertEquals(Channel.MeshDM("b0b"), resolve(null))
    }
}
