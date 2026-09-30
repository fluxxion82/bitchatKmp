package com.bitchat.viewmodel.chat

import com.bitchat.domain.app.model.ActiveState
import com.bitchat.domain.app.model.UserState
import com.bitchat.domain.base.CoroutinesContextFacade
import com.bitchat.domain.chat.ObserveChannelMessages
import com.bitchat.domain.chat.GetJoinedNamedChannels
import com.bitchat.domain.chat.eventbus.InMemoryChatEventBus
import com.bitchat.domain.chat.model.BitchatMessage
import com.bitchat.domain.chat.model.ChatEvent
import com.bitchat.domain.chat.model.failure.CommandFailure
import com.bitchat.domain.location.model.Channel
import com.bitchat.domain.user.GetUserNickname
import com.bitchat.domain.user.GetUserState
import com.bitchat.domain.user.model.UserStateAction
import com.bitchat.viewmodel.BaseViewModelTest
import com.bitchat.viewmodel.chat.DATA_BEING_CLEARED
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * The owner's sequence: in `#test`, leave it, and the chat must show the mesh. `SaveUserStateAction`
 * announces the switch twice on the chat event bus: once from `setSelectedChannel`, before the new
 * user state is saved, and once after. The view model must end on the saved state whichever of the
 * two it happens to read the state for.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelChannelSwitchTest : BaseViewModelTest() {
    private val dispatcher get() = instantExecutorRule.testDispatcher

    private val facade = object : CoroutinesContextFacade {
        override val io: CoroutineContext get() = dispatcher
        override val main: CoroutineContext get() = dispatcher
        override val default: CoroutineContext get() = dispatcher
        override val unconfined: CoroutineContext get() = dispatcher
    }
    private val bus = InMemoryChatEventBus(facade)

    private var joins = 0

    /** Answers `/who` only when the test says so. */
    private val slowPeers = kotlinx.coroutines.CompletableDeferred<List<com.bitchat.domain.location.model.GeoPerson>>()

    /** The app-wide store of command feedback. */
    private val notices = com.bitchat.domain.chat.ChatNotices()

    /** What the view model asked the app to switch to, in order. */
    private val switched = mutableListOf<com.bitchat.domain.user.model.UserStateAction>()

    /** The channels the user is in, as the chat repository has them. */
    private var joined = listOf("#test")

    /** Set by a test that wants `getUserState()` to suspend part way through a command. */
    private var userStateGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    /** The saved user state's active chat, and the one before it. */
    private var active: Channel = Channel.NamedChannel("#test")
    private var previous: Channel? = null

    private fun message(id: String, at: Long = 0) =
        BitchatMessage(id = id, sender = "anon", content = id, timestamp = Instant.fromEpochSeconds(at))

    /** A message dated far enough ahead that no line made now can be later than it. */
    private val dated = message("dated 3000", at = 32_503_680_000)

    private val lists = mapOf<Channel, List<BitchatMessage>>(
        Channel.NamedChannel("#test") to listOf(message("hello #test")),
        Channel.NamedChannel("#later") to listOf(dated),
        Channel.Mesh to listOf(message("mesh 1"), message("mesh 2")),
    )

    private fun buildViewModel() = ChatViewModel(
        observeChannelMessages = mockk<ObserveChannelMessages> {
            coEvery { this@mockk.invoke(any()) } answers { flowOf(lists[firstArg<Channel>()].orEmpty()) }
        },
        sendMessage = mockk(relaxed = true),
        processChatCommand = com.bitchat.domain.chat.ProcessChatCommand(),
        getUserState = mockk<GetUserState> {
            coEvery { this@mockk.invoke(Unit) } coAnswers {
                userStateGate?.await()
                UserState.Active(ActiveState.Chat(active))
            }
        },
        chatEventBus = bus,
        getUserNickname = mockk<GetUserNickname> { coEvery { this@mockk.invoke(Unit) } returns flowOf("anon") },
        saveUserStateAction = mockk<com.bitchat.domain.user.SaveUserStateAction>(relaxed = true) {
            coEvery { this@mockk.invoke(capture(switched)) } returns Unit
        },
        leaveChannel = com.bitchat.domain.chat.LeaveChannel(
            chatRepository = mockk(relaxed = true),
            chatEventBus = bus,
            chatNotices = notices,
        ),
        getJoinedNamedChannels = mockk<GetJoinedNamedChannels> { coEvery { this@mockk.invoke(Unit) } returns emptyFlow() },
        getGeohashParticipants = mockk(relaxed = true),
        getMeshPeers = mockk<com.bitchat.domain.chat.GetMeshPeers> {
            coEvery { this@mockk.invoke(Unit) } coAnswers { slowPeers.await() }
        },
        getChannelKeyCommitment = mockk(relaxed = true),
        getAvailableNamedChannels = mockk(relaxed = true),
        getChannelMembers = mockk(relaxed = true),
        blockUser = mockk(relaxed = true),
        unblockUser = mockk(relaxed = true),
        getBlockedUsers = mockk(relaxed = true),
        chatNotices = notices,
        resolveChatFallback = com.bitchat.domain.chat.ResolveChatFallback(
            userRepository = mockk { coEvery { getUserState() } answers { UserState.Active(ActiveState.Chat(active, previousChannel = previous)) } },
            chatRepository = mockk { coEvery { getJoinedChannelsList() } answers { joined } },
        ),
        joinChannel = mockk<com.bitchat.domain.chat.JoinChannel> {
            coEvery { this@mockk.invoke(any()) } answers {
                val name = "#" + firstArg<com.bitchat.domain.chat.JoinChannel.Params>().channelName.removePrefix("#")
                com.bitchat.domain.base.model.Outcome.Success(
                    com.bitchat.domain.chat.JoinChannel.JoinChannelResult(
                        com.bitchat.domain.chat.model.ChannelInfo(name, false, 1, null, null, false, null),
                        isNewChannel = joins++ == 0,
                    )
                )
            }
        },
        setChannelPassword = mockk(relaxed = true),
        clearMessages = com.bitchat.domain.chat.ClearMessages(chatRepository = mockk(relaxed = true), chatNotices = notices),
    )

    @Test
    fun `leaving a channel shows the mesh even when the first announcement is read before the state is saved`() = runTest(dispatcher) {
        val viewModel = buildViewModel()
        instantExecutorRule.scheduler.advanceUntilIdle()
        assertEquals(listOf("hello #test"), viewModel.state.value.messages.map { it.content })

        // SaveUserStateAction(Chat(Mesh)): setSelectedChannel announces first...
        bus.update(ChatEvent.SelectedPrivatePeerChanged)
        bus.update(ChatEvent.ChannelChanged)
        instantExecutorRule.scheduler.advanceUntilIdle() // ...and the view model reads the state, still #test
        active = Channel.Mesh // then the state is saved
        bus.update(ChatEvent.ChannelChanged) // and announced again
        instantExecutorRule.scheduler.advanceUntilIdle()

        assertEquals(Channel.Mesh, viewModel.state.value.selectedLocationChannel)
        assertEquals(listOf("mesh 1", "mesh 2"), viewModel.state.value.messages.map { it.content })
    }

    @Test
    fun `the feedback of joining a channel is shown in that channel once it is on screen`() = runTest(dispatcher) {
        active = Channel.Mesh
        val viewModel = buildViewModel()
        instantExecutorRule.scheduler.advanceUntilIdle()
        viewModel.updateMessageInput("/j test")
        viewModel.sendMessage()
        instantExecutorRule.scheduler.advanceUntilIdle()
        active = Channel.NamedChannel("#test") // what SaveUserStateAction saved
        bus.update(ChatEvent.ChannelChanged)
        instantExecutorRule.scheduler.advanceUntilIdle()
        assertEquals(listOf("hello #test", "created channel #test"), viewModel.state.value.messages.map { it.content })

        viewModel.updateMessageInput("/j test") // again, already in it
        viewModel.sendMessage()
        instantExecutorRule.scheduler.advanceUntilIdle()
        assertEquals(listOf("hello #test", "created channel #test", "joined channel #test"), viewModel.state.value.messages.map { it.content })
    }

    @Test
    fun `leaving the channel by a different spelling still leaves it`() = runTest(dispatcher) {
        active = Channel.NamedChannel("#test")
        previous = Channel.NamedChannel("#test") // a state saved before joins stopped doing this
        val viewModel = buildViewModel()
        instantExecutorRule.scheduler.advanceUntilIdle()
        viewModel.updateMessageInput("/leave test") // no "#", as a user types it
        viewModel.sendMessage()
        instantExecutorRule.scheduler.advanceUntilIdle()
        assertEquals(listOf(UserStateAction.Chat(Channel.Mesh)), switched.toList())
    }

    @Test
    fun `leaving does not return to a channel already left`() = runTest(dispatcher) {
        active = Channel.NamedChannel("#a")
        previous = Channel.NamedChannel("#b") // "/j a", "/j b", "/leave" landed here; #b is gone
        joined = listOf("#a")
        val viewModel = buildViewModel()
        instantExecutorRule.scheduler.advanceUntilIdle()
        viewModel.updateMessageInput("/leave")
        viewModel.sendMessage()
        instantExecutorRule.scheduler.advanceUntilIdle()
        assertEquals(listOf(UserStateAction.Chat(Channel.Mesh)), switched.toList())
    }

    @Test
    fun `a command's answer goes to the chat it was typed in, not the one on screen when it lands`() = runTest(dispatcher) {
        active = Channel.Mesh
        val viewModel = buildViewModel()
        instantExecutorRule.scheduler.advanceUntilIdle()
        viewModel.updateMessageInput("/who")
        viewModel.sendMessage()
        instantExecutorRule.scheduler.advanceUntilIdle() // still waiting for the peer list

        active = Channel.NamedChannel("#test") // the user moves to #test meanwhile
        bus.update(ChatEvent.ChannelChanged)
        instantExecutorRule.scheduler.advanceUntilIdle()
        slowPeers.complete(emptyList())
        instantExecutorRule.scheduler.advanceUntilIdle()

        assertEquals(listOf("hello #test"), viewModel.state.value.messages.map { it.content }, "#test is untouched")
        assertEquals(listOf("no peers connected"), notices.of(Channel.Mesh).map { it.message.content })
    }

    @Test
    fun `joining a channel puts the feedback below what that channel already holds`() = runTest(dispatcher) {
        // The channel joined into is not the one on screen when the line is made, so its own
        // newest message is what the line has to go below, not the one the user is looking at.
        active = Channel.Mesh
        val viewModel = buildViewModel()
        instantExecutorRule.scheduler.advanceUntilIdle()
        viewModel.updateMessageInput("/j later")
        viewModel.sendMessage()
        instantExecutorRule.scheduler.advanceUntilIdle()

        active = Channel.NamedChannel("#later")
        bus.update(ChatEvent.ChannelChanged)
        instantExecutorRule.scheduler.advanceUntilIdle()
        assertEquals(listOf("dated 3000", "created channel #later"), viewModel.state.value.messages.map { it.content })
    }

    @Test
    fun `a failed command is answered in the chat it was typed in`() = runTest(dispatcher) {
        active = Channel.Mesh
        val viewModel = buildViewModel()
        instantExecutorRule.scheduler.advanceUntilIdle()
        viewModel.updateMessageInput("/hug") // No one to hug: the screen turns this into a line.
        viewModel.sendMessage()
        instantExecutorRule.scheduler.advanceUntilIdle()
        val pending = viewModel.state.value.pendingCommandFailure
        assertEquals(CommandFailure.MissingTarget, pending?.failure)
        assertEquals(Channel.Mesh, pending?.channel)

        active = Channel.NamedChannel("#test") // The user moves on before the screen gets to it.
        bus.update(ChatEvent.ChannelChanged)
        instantExecutorRule.scheduler.advanceUntilIdle()
        viewModel.postCommandFailure(pending!!, "command requires a target")
        instantExecutorRule.scheduler.advanceUntilIdle()

        assertEquals(listOf("hello #test"), viewModel.state.value.messages.map { it.content }, "#test is untouched")
        assertEquals(listOf("command requires a target"), notices.of(Channel.Mesh).map { it.message.content })
        assertEquals(null, viewModel.state.value.pendingCommandFailure)
        viewModel.postCommandFailure(pending, "command requires a target") // Already answered.
        instantExecutorRule.scheduler.advanceUntilIdle()
        assertEquals(1, notices.of(Channel.Mesh).size, "the same failure is answered once")
    }

    @Test
    fun `a command running while the data is wiped answers into nothing`() = runTest(dispatcher) {
        active = Channel.Mesh
        val viewModel = buildViewModel()
        instantExecutorRule.scheduler.advanceUntilIdle()
        viewModel.updateMessageInput("/who")
        viewModel.sendMessage()
        instantExecutorRule.scheduler.advanceUntilIdle() // Still waiting for the peer list.

        notices.reset {} // The user wipes their data: a new identity from here on.
        slowPeers.complete(listOf(com.bitchat.domain.location.model.GeoPerson("b0b", "bob", Instant.fromEpochSeconds(1))))
        instantExecutorRule.scheduler.advanceUntilIdle()
        assertEquals(emptyList(), notices.of(Channel.Mesh), "the old identity's peers are not shown to the new one")
    }

    @Test
    fun `a command that began before a wipe cannot answer after it`() = runTest(dispatcher) {
        active = Channel.Mesh
        val viewModel = buildViewModel()
        instantExecutorRule.scheduler.advanceUntilIdle()

        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        userStateGate = gate
        viewModel.updateMessageInput("/hug") // No one to hug, but it has to get that far first.
        viewModel.sendMessage()
        instantExecutorRule.scheduler.advanceUntilIdle() // Suspended inside the command.

        notices.reset {} // The user wipes their data while it is suspended.
        gate.complete(Unit)
        instantExecutorRule.scheduler.advanceUntilIdle()

        val pending = viewModel.state.value.pendingCommandFailure
        assertEquals(CommandFailure.MissingTarget, pending?.failure)
        viewModel.postCommandFailure(pending!!, "command requires a target")
        instantExecutorRule.scheduler.advanceUntilIdle()
        assertEquals(emptyList(), notices.of(Channel.Mesh), "the answer belongs to the identity that asked")
    }

    @Test
    fun `no command starts while the data is being wiped`() = runTest(dispatcher) {
        active = Channel.Mesh
        val viewModel = buildViewModel()
        instantExecutorRule.scheduler.advanceUntilIdle()
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        val wipe = kotlinx.coroutines.CoroutineScope(dispatcher).launch {
            notices.reset {
                started.complete(Unit)
                kotlinx.coroutines.delay(1_000)
            }
        }
        instantExecutorRule.scheduler.advanceTimeBy(1)
        assertEquals(true, started.isCompleted, "the wipe is under way")

        viewModel.updateMessageInput("/j test")
        assertEquals(false, viewModel.sendMessage(), "refused, so the caller keeps the line")
        instantExecutorRule.scheduler.advanceUntilIdle()
        assertEquals(DATA_BEING_CLEARED, viewModel.state.value.errorMessage, "and is told why")
        wipe.join()
        assertEquals(emptyList(), switched.toList(), "the half-cleared stores were left alone")
        assertEquals("/j test", viewModel.state.value.messageInput, "and the line is still typed")

        // Once the wipe is done the same line goes through.
        assertEquals(true, viewModel.sendMessage())
    }

    @Test
    fun `leaving a channel drops the feedback filed under it`() = runTest(dispatcher) {
        active = Channel.NamedChannel("#test")
        val viewModel = buildViewModel()
        instantExecutorRule.scheduler.advanceUntilIdle()
        notices.add(Channel.NamedChannel("#test"), message("joined channel #test"))
        viewModel.updateMessageInput("/leave")
        viewModel.sendMessage()
        instantExecutorRule.scheduler.advanceUntilIdle()
        assertEquals(emptyList(), notices.of(Channel.NamedChannel("#test")), "the channel is gone and so are its lines")
    }

    @Test
    fun `feedback survives a new chat screen for the same chat`() = runTest(dispatcher) {
        // Compose gives each chat destination its own ChatViewModel; the feedback that led there
        // (and what /clear does to it) belongs to the app, not to one screen.
        active = Channel.Mesh
        val first = buildViewModel()
        instantExecutorRule.scheduler.advanceUntilIdle()
        first.updateMessageInput("/j test")
        first.sendMessage()
        instantExecutorRule.scheduler.advanceUntilIdle()

        active = Channel.NamedChannel("#test")
        val second = buildViewModel() // the new destination
        instantExecutorRule.scheduler.advanceUntilIdle()
        assertEquals(listOf("hello #test", "created channel #test"), second.state.value.messages.map { it.content })

        second.updateMessageInput("/clear")
        second.sendMessage()
        instantExecutorRule.scheduler.advanceUntilIdle()
        assertEquals(emptyList(), notices.of(Channel.NamedChannel("#test")).map { it.message.content })
    }
}
