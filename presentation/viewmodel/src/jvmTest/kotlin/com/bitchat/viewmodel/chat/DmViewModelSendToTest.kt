package com.bitchat.viewmodel.chat

import com.bitchat.domain.chat.MarkPrivateChatRead
import com.bitchat.domain.chat.ObserveLatestUnreadPrivatePeer
import com.bitchat.domain.chat.ObservePrivateChats
import com.bitchat.domain.chat.ObserveSelectedPrivatePeer
import com.bitchat.domain.chat.ObserveUnreadPrivatePeers
import com.bitchat.domain.chat.SendMessage
import com.bitchat.domain.location.model.Channel
import com.bitchat.domain.user.GetUserNickname
import com.bitchat.viewmodel.BaseViewModelTest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * [DmViewModel.sendTo] sends to the DM it is given. The active chat can change between the user
 * pressing Enter and the send running (another coroutine, another thread on Linux), so it must not
 * be read at all, and a public channel must never be accepted as a DM.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DmViewModelSendToTest : BaseViewModelTest() {
    private val sendMessage = mockk<SendMessage>(relaxed = true)


    private fun buildViewModel() = DmViewModel(
        observePrivateChats = mockk<ObservePrivateChats> { coEvery { this@mockk.invoke(Unit) } returns emptyFlow() },
        observeUnreadPrivatePeers = mockk<ObserveUnreadPrivatePeers> { coEvery { this@mockk.invoke(Unit) } returns emptyFlow() },
        observeLatestUnreadPrivatePeer = mockk<ObserveLatestUnreadPrivatePeer> { coEvery { this@mockk.invoke(Unit) } returns emptyFlow() },
        observeSelectedPrivatePeer = mockk<ObserveSelectedPrivatePeer> { coEvery { this@mockk.invoke(Unit) } returns emptyFlow() },
        markPrivateChatRead = mockk<MarkPrivateChatRead>(relaxed = true),
        sendMessage = sendMessage,
        getUserNickname = mockk<GetUserNickname> { coEvery { this@mockk.invoke(Unit) } returns flowOf("anon") },
    ).also { instantExecutorRule.scheduler.runCurrent() }

    @Test
    fun `a line goes to the DM it was sent in even after the active chat changed`() {
        val viewModel = buildViewModel()
        val bob = Channel.MeshDM("b0b", "bob")

        viewModel.sendTo(bob, "meet at noon")
        instantExecutorRule.scheduler.runCurrent()

        coVerify(exactly = 1) { sendMessage.invoke(SendMessage.Params(content = "meet at noon", channel = bob, sender = "anon")) }
        coVerify(exactly = 0) { sendMessage.invoke(match { it.channel == Channel.Mesh }) }
    }

    @Test
    fun `a public channel is refused`() {
        val viewModel = buildViewModel()

        for (channel in listOf(Channel.Mesh, Channel.NamedChannel("#general"), Channel.Meshtastic(nodeNum = null), Channel.Location(com.bitchat.domain.location.model.GeohashChannelLevel.CITY, "9q8yy"))) {
            viewModel.sendTo(channel, "secret")
            instantExecutorRule.scheduler.runCurrent()
        }

        coVerify(exactly = 0) { sendMessage.invoke(any()) }
        assertNotNull(viewModel.state.value.errorMessage)
    }

    @Test
    fun `a blank line sends nothing`() {
        val viewModel = buildViewModel()
        viewModel.sendTo(Channel.MeshDM("b0b"), "   ")
        instantExecutorRule.scheduler.runCurrent()
        coVerify(exactly = 0) { sendMessage.invoke(any()) }
        assertEquals(null, viewModel.state.value.errorMessage)
    }

    @Test
    fun `a LoRa node DM is refused until LoRa DMs exist, instead of going out as a broadcast`() {
        val viewModel = buildViewModel()
        viewModel.sendTo(Channel.Meshtastic(nodeNum = 0x1234), "secret")
        instantExecutorRule.scheduler.runCurrent()
        coVerify(exactly = 0) { sendMessage.invoke(any()) }
        assertNotNull(viewModel.state.value.errorMessage)
    }


    @Test
    fun `a refusal is immediate and says whether the line was taken`() {
        val viewModel = buildViewModel()
        // No coroutine runs between the call and the answer: a Compose or TUI caller keeps the
        // draft on false, and nothing is left to look the destination up later.
        assertEquals(false, viewModel.sendTo(Channel.Meshtastic(nodeNum = 0x1234), "draft"))
        assertEquals("Not sent: LoRa DMs are not supported yet", viewModel.state.value.errorMessage)
        assertEquals(false, viewModel.sendTo(null, "draft"))
        assertEquals(true, viewModel.sendTo(Channel.MeshDM("b0b"), "hi"))
        instantExecutorRule.scheduler.runCurrent()
        coVerify(exactly = 1) { sendMessage.invoke(any()) }
    }
}
