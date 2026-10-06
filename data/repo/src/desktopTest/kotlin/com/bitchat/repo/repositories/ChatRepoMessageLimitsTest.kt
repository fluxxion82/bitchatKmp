package com.bitchat.repo.repositories

import com.bitchat.bluetooth.model.PeerInfo
import com.bitchat.bluetooth.service.BluetoothMeshService
import com.bitchat.domain.chat.model.BitchatMessage
import com.bitchat.domain.chat.model.BitchatMessageType
import com.bitchat.domain.location.model.Channel
import com.bitchat.cache.impl.InMemoryCache
import com.bitchat.nostr.NostrEmbeddedBitChat
import com.bitchat.nostr.NostrSubscriptionId
import com.bitchat.nostr.model.NostrEvent
import com.bitchat.nostr.model.NostrIdentity
import com.bitchat.nostr.model.NostrKind
import com.bitchat.repo.utils.MessageLimits
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ChatRepoMessageLimitsTest {
    private val smallLimits = MessageLimits(maxMessagesPerChat = 5, maxCharsPerChat = 100, maxStrangerMessages = 8)

    @Test
    fun meshMessagesDropTheOldestAfterThePerChatLimit() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)

        try {
            val repo = chatRepo(scope, dispatcher, mutableListOf())
            repeat(1_338) { index ->
                repo.didReceiveMessage(
                    BitchatMessage(
                        id = "mesh-$index",
                        sender = "peer",
                        senderPeerID = "peer",
                        content = "message-$index",
                        timestamp = Instant.fromEpochSeconds(index.toLong()),
                    ),
                )
            }

            val messages = repo.getMeshMessages()
            assertEquals(1_337, messages.size)
            assertEquals("mesh-1", messages.first().id)
            assertEquals("mesh-1337", messages.last().id)
        } finally {
            scope.cancel()
        }
    }

    @Test fun meshMessagesDropTheOldestPastTheCharacterLimit() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            val repo = chatRepo(scope, dispatcher, mutableListOf(), messageLimits = smallLimits)
            listOf("a".repeat(40), "b".repeat(40), "c".repeat(40)).forEachIndexed { index, content ->
                repo.didReceiveMessage(message("mesh-$index", content, index.toLong()))
            }

            assertEquals(listOf("mesh-1", "mesh-2"), repo.getMeshMessages().map { it.id })
        } finally {
            scope.cancel()
        }
    }

    @Test fun meshMessageNamingAChannelIsDropped() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            val repo = chatRepo(scope, dispatcher, mutableListOf(), messageLimits = smallLimits)
            repo.didReceiveMessage(message("named", "hello", 0).copy(channel = "abc"))

            assertTrue(repo.getMeshMessages().isEmpty())
            assertTrue(repo.getGeohashMessages("abc").isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test fun authenticatedPrivateMessagesAreLimitedAndFutureDatesDoNotWin() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            val clock = TestClock()
            val repo = chatRepo(scope, dispatcher, mutableListOf(), clock = clock, messageLimits = smallLimits)
            repo.didReceiveAuthenticatedPrivateMessage(message("future", "future", (clock.now() + 1.days).epochSeconds).copy(isPrivate = true))
            repeat(5) { index ->
                clock.advance()
                repo.didReceiveAuthenticatedPrivateMessage(message("private-$index", "message", clock.now().epochSeconds).copy(isPrivate = true))
            }

            assertEquals((0..4).map { "private-$it" }, repo.getPrivateChats().getValue("peer").map { it.id })
        } finally {
            scope.cancel()
        }
    }

    @Test fun namedChannelMessagesAreLimited() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            val repo = chatRepo(scope, dispatcher, mutableListOf(), messageLimits = smallLimits)
            repeat(6) { index -> repo.addNamedChannelMessage("test", message("named-$index", "x", index.toLong())) }

            assertEquals((1..5).map { "named-$it" }, repo.getNamedChannelMessages("test").map { it.id })
        } finally {
            scope.cancel()
        }
    }

    @Test fun oversizedPublicSendIsRefusedBeforeItCreatesALocalEcho() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            val repo = chatRepo(scope, dispatcher, mutableListOf())

            assertFailsWith<IllegalArgumentException> {
                repo.sendMessage("x".repeat(BitchatMessage.MAX_CONTENT_CHARS + 1), Channel.Mesh, "me", BitchatMessageType.Message)
            }
            assertTrue(repo.getMeshMessages().isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aFloodDatedAheadCannotMakeALaterHonestMessageTheFirstToGo() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            val clock = FractionalClock()
            val repo = chatRepo(scope, dispatcher, mutableListOf(), clock = clock, messageLimits = smallLimits)
            // Five messages dated a day ahead arrive within one second; each is stamped with the
            // fraction of the second it arrived in.
            repeat(5) { index ->
                clock.advanceMillis(100)
                repo.addNamedChannelMessage("test", message("flood-$index", "x", clock.now().epochSeconds + 86_400))
            }
            // An honest message written at the start of that same second (Nostr timestamps are whole
            // seconds) arrives after them: by its timestamp it is the oldest in the chat.
            clock.advanceMillis(100)
            repo.addNamedChannelMessage("test", message("honest", "x", 1_000))

            val shown = repo.getNamedChannelMessages("test").map { it.id }
            assertEquals(listOf("honest", "flood-1", "flood-2", "flood-3", "flood-4"), shown)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aMessageThatArrivesLateIsKeptAndShownWhereItsTimestampPutsIt() = withRepo(smallLimits) { repo, _ ->
        val now = Clock.System.now().epochSeconds
        repeat(5) { index -> repo.addNamedChannelMessage("test", message("recent-$index", "x", now - 10 + index)) }

        // Written an hour ago, delivered now, into a full chat: the oldest ARRIVAL goes, not this one.
        repo.addNamedChannelMessage("test", message("delayed", "x", now - 3_600))

        assertEquals(
            listOf("delayed", "recent-1", "recent-2", "recent-3", "recent-4"),
            repo.getNamedChannelMessages("test").map { it.id },
        )
    }

    @Test
    fun aPrivateMessageThatArrivesLateIsKeptToo() = withRepo(smallLimits) { repo, _ ->
        repeat(5) { index -> repo.didReceiveAuthenticatedPrivateMessage(privateMessage("peer", "recent-$index", 1_000L + index)) }

        repo.didReceiveAuthenticatedPrivateMessage(privateMessage("peer", "delayed", 10))

        assertEquals(
            listOf("delayed", "recent-1", "recent-2", "recent-3", "recent-4"),
            repo.getPrivateChats().getValue("peer").map { it.id },
        )
    }

    @Test
    fun geohashMessagesFromRelaysAreLimitedByArrivalToo() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val subscriptions = mutableListOf<Subscription>()
        try {
            val clock = FractionalClock()
            val repo = chatRepo(scope, dispatcher, subscriptions, clock = clock, messageLimits = smallLimits)
            repo.getGeohashMessages(GEOHASH)
            val relay = subscriptions.single { it.id == NostrSubscriptionId.geohash(GEOHASH) }.handler
            val now = clock.now().epochSeconds.toInt()

            // Seven events dated a day ahead, each from a key of its own, then one honest event written
            // this second, then one written an hour ago and only delivered now.
            repeat(7) { index -> relay(geohashEvent("flood-$index", createdAt = now + 86_400)) }
            relay(geohashEvent("honest", createdAt = now))
            relay(geohashEvent("delayed", createdAt = now - 3_600))
            // A message longer than the limit is not stored at all.
            relay(geohashEvent("huge", createdAt = now, content = "x".repeat(BitchatMessage.MAX_CONTENT_CHARS + 1)))

            assertEquals(
                listOf("delayed", "flood-4", "flood-5", "flood-6", "honest"),
                repo.getGeohashMessages(GEOHASH).map { it.id },
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aChatTheUserWroteInStaysOutsideTheStrangersBudgetAfterThatMessageIsGone() = withRepo(
        MessageLimits(maxMessagesPerChat = 3, maxCharsPerChat = 1_000, maxStrangerMessages = 4),
    ) { repo, mesh ->
        every { mesh.getPeerInfo("friend") } returns mockk<PeerInfo>(relaxed = true).also { every { it.isConnected } returns true }
        every { mesh.hasEstablishedSession("friend") } returns true
        repo.sendMessage("hello", Channel.MeshDM("friend"), "me", BitchatMessageType.Message)
        repeat(3) { index -> repo.didReceiveAuthenticatedPrivateMessage(privateMessage("friend", "f-$index", 10L + index)) }
        assertEquals(listOf("f-0", "f-1", "f-2"), repo.getPrivateChats().getValue("friend").map { it.id }, "the user's own message was the oldest")

        repeat(6) { index -> repo.didReceiveAuthenticatedPrivateMessage(privateMessage("stranger-$index", "s-$index", 100L + index)) }

        val chats = repo.getPrivateChats()
        assertEquals(listOf("f-0", "f-1", "f-2"), chats.getValue("friend").map { it.id }, "with three messages it would be the largest, and it is not touched")
        assertEquals(4, chats.filterKeys { it != "friend" }.values.sumOf { it.size })
    }

    @Test
    fun messagesWithTheSameTimestampAreShownInTheOrderTheyArrived() = withRepo(smallLimits) { repo, _ ->
        listOf("first", "second", "third").forEach { id ->
            repo.didReceiveAuthenticatedPrivateMessage(privateMessage("peer", id, 500))
        }

        assertEquals(listOf("first", "second", "third"), repo.getPrivateChats().getValue("peer").map { it.id })
    }

    @Test
    fun privateMessagesFromManyThreadsKeepTheChatsWithinTheBudgetAndConsistent() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val limits = MessageLimits(maxMessagesPerChat = 10, maxCharsPerChat = 10_000, maxStrangerMessages = 50)
        val repo = chatRepo(scope, dispatcher, mutableListOf(), messageLimits = limits)
        val threads = 8
        val pool = Executors.newFixedThreadPool(threads)
        val begin = CountDownLatch(1)
        val failures = AtomicInteger()
        try {
            repeat(threads) { thread ->
                pool.execute {
                    begin.await()
                    try {
                        repeat(300) { index ->
                            // Each thread writes into chats of its own and into one that all share.
                            val peer = if (index % 3 == 0) "shared" else "peer-$thread-${index % 20}"
                            repo.didReceiveAuthenticatedPrivateMessage(privateMessage(peer, "m-$thread-$index", index.toLong()))
                        }
                    } catch (e: Throwable) {
                        failures.incrementAndGet()
                    }
                }
            }
            begin.countDown()
            pool.shutdown()
            assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "the handlers did not finish")

            assertEquals(0, failures.get(), "a handler failed while another was adding")
            val chats = repo.getPrivateChats()
            assertTrue(chats.values.sumOf { it.size } <= 50, "strangers hold ${chats.values.sumOf { it.size }} messages")
            assertTrue(chats.values.none { it.isEmpty() }, "an emptied chat was left behind")
            assertEquals(chats.keys, repo.getUnreadPrivatePeers(), "every chat that is left has unread messages, and no other does")
        } finally {
            pool.shutdownNow()
            scope.cancel()
        }
    }

    @Test
    fun strangersShareOneBudgetAndAnEmptiedChatIsRemoved() = withRepo(smallLimits) { repo, _ ->
        // Nine keys, one message each, against a budget of eight for chats the user has not written in.
        repeat(9) { index -> repo.didReceiveAuthenticatedPrivateMessage(privateMessage("stranger-$index", "m-$index", index.toLong())) }

        val chats = repo.getPrivateChats()
        assertEquals((1..8).map { "stranger-$it" }.toSet(), chats.keys, "the chat opened first is gone, not left empty; the newest is there")
        assertEquals(8, chats.values.sumOf { it.size })
        assertFalse("stranger-0" in repo.getUnreadPrivatePeers(), "a removed chat is not unread")
        assertTrue(repo.getLatestUnreadPrivatePeer() != "stranger-0")
    }

    @Test
    fun oneStrangerSendingALotOnlyCostsItsOwnMessages() = withRepo(
        MessageLimits(maxMessagesPerChat = 10, maxCharsPerChat = 1_000, maxStrangerMessages = 8),
    ) { repo, _ ->
        repeat(3) { index -> repo.didReceiveAuthenticatedPrivateMessage(privateMessage("quiet-$index", "q-$index", index.toLong())) }

        repeat(12) { index -> repo.didReceiveAuthenticatedPrivateMessage(privateMessage("loud", "l-$index", 100L + index)) }

        val chats = repo.getPrivateChats()
        for (index in 0 until 3) assertEquals(listOf("q-$index"), chats.getValue("quiet-$index").map { it.id })
        // The loud chat is the largest every time the budget is passed, so it is the one that gives way.
        assertEquals((7..11).map { "l-$it" }, chats.getValue("loud").map { it.id })
        assertEquals(setOf("quiet-0", "quiet-1", "quiet-2", "loud"), repo.getUnreadPrivatePeers())
    }

    @Test
    fun strangersAlsoShareABudgetOfCharactersAndTheChatHoldingTheMostGivesWay() = withRepo(
        MessageLimits(maxMessagesPerChat = 10, maxCharsPerChat = 1_000, maxStrangerMessages = 100, maxStrangerChars = 300),
    ) { repo, _ ->
        // Few messages, so the count is nowhere near; it is their length that passes the budget.
        repeat(2) { index -> repo.didReceiveAuthenticatedPrivateMessage(privateMessage("brief-$index", "b-$index", index.toLong(), "x".repeat(20))) }
        repeat(4) { index -> repo.didReceiveAuthenticatedPrivateMessage(privateMessage("wordy", "w-$index", 10L + index, "x".repeat(100))) }

        val chats = repo.getPrivateChats()
        for (index in 0 until 2) assertEquals(listOf("b-$index"), chats.getValue("brief-$index").map { it.id })
        assertEquals(listOf("w-2", "w-3"), chats.getValue("wordy").map { it.id }, "the chat with the most characters lost its oldest")
        assertTrue(chats.values.sumOf { chat -> chat.sumOf { it.content.length } } <= 300)
    }

    @Test
    fun theMessageThatJustArrivedIsNeverTheOneTheBudgetDrops() = withRepo(
        MessageLimits(maxMessagesPerChat = 10, maxCharsPerChat = 1_000, maxStrangerMessages = 100, maxStrangerChars = 300),
    ) { repo, _ ->
        repeat(2) { chat ->
            repeat(2) { index -> repo.didReceiveAuthenticatedPrivateMessage(privateMessage("earlier-$chat", "e-$chat-$index", 1, "x".repeat(50))) }
        }

        // One message that alone makes its chat the one holding the most characters.
        repo.didReceiveAuthenticatedPrivateMessage(privateMessage("newcomer", "big", 2, "x".repeat(250)))

        val chats = repo.getPrivateChats()
        assertEquals(listOf("big"), chats.getValue("newcomer").map { it.id }, "whatever was acknowledged or marked unread is there")
        assertTrue(chats.values.sumOf { chat -> chat.sumOf { it.content.length } } <= 300)

        // Its next message finds that chat the largest with an older message in it: now it pays itself.
        repo.didReceiveAuthenticatedPrivateMessage(privateMessage("newcomer", "second", 3, "x".repeat(50)))
        assertEquals(listOf("second"), repo.getPrivateChats().getValue("newcomer").map { it.id })
    }

    @Test
    fun aRemovedChatTakesTheKeyKeptForItAlong() = withRepo(
        MessageLimits(maxMessagesPerChat = 10, maxCharsPerChat = 1_000, maxStrangerMessages = 4),
    ) { repo, _ ->
        repo.storePersonDataForDM("nostr_gone", "cd".repeat(32), sourceGeohash = null, displayName = null)
        repo.didReceiveAuthenticatedPrivateMessage(privateMessage("nostr_gone", "g-0", 0))

        repeat(4) { index -> repo.didReceiveAuthenticatedPrivateMessage(privateMessage("stranger-$index", "s-$index", 10L + index)) }

        assertFalse("nostr_gone" in repo.getPrivateChats())
        assertEquals(null, repo.getFullPubkey("nostr_gone"))
    }

    @Test
    fun aNostrMessageBringsTheKeyItIsAnsweredWithEveryTimeItsChatIsOpenedAgain() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val subscriptions = mutableListOf<Subscription>()
        try {
            val limits = MessageLimits(maxMessagesPerChat = 10, maxCharsPerChat = 1_000, maxStrangerMessages = 4)
            val repo = chatRepo(scope, dispatcher, subscriptions, messageLimits = limits, nostrIdentity = ME)
            val relay = subscriptions.single { it.id == NostrSubscriptionId.directMessages(ME.publicKeyHex) }.handler
            val sender = nostrKey(0)

            relay(giftWrap("wrap-1", sender, "first"))
            assertEquals(listOf("first"), repo.getPrivateChats().getValue(chatOf(sender)).map { it.content })
            assertEquals(sender, repo.getFullPubkey(chatOf(sender)))

            // Four more keys push that chat out, and its key with it.
            repeat(4) { index -> relay(giftWrap("flood-$index", nostrKey(10 + index), "hi")) }
            assertFalse(chatOf(sender) in repo.getPrivateChats())
            assertEquals(null, repo.getFullPubkey(chatOf(sender)))

            relay(giftWrap("wrap-2", sender, "second"))
            assertEquals(listOf("second"), repo.getPrivateChats().getValue(chatOf(sender)).map { it.content })
            assertEquals(sender, repo.getFullPubkey(chatOf(sender)), "a chat that is there can be answered")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aGeohashDirectMessageRecordsWhereItIsAnsweredAndARemovedChatTakesThatAlong() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val subscriptions = mutableListOf<Subscription>()
        val alias = InMemoryCache<String, String>()
        val conversation = InMemoryCache<String, String>()
        try {
            val repo = chatRepo(
                scope, dispatcher, subscriptions,
                messageLimits = MessageLimits(maxMessagesPerChat = 10, maxCharsPerChat = 1_000, maxStrangerMessages = 4),
                geohashIdentity = ME,
                geohashAliasCache = alias,
                geohashConversationCache = conversation,
            )
            repo.getGeohashMessages(GEOHASH)
            val relay = subscriptions.single { it.id == NostrSubscriptionId.geohashDirectMessages(GEOHASH) }.handler
            val sender = nostrKey(0)
            val chat = chatOf(sender)

            relay(giftWrap("wrap-1", sender, "first"))
            assertEquals(listOf(sender, GEOHASH), listOf(alias[chat], conversation[chat]))

            repeat(4) { index -> relay(giftWrap("flood-$index", nostrKey(10 + index), "hi")) }
            assertEquals(listOf<String?>(null, null), listOf(alias[chat], conversation[chat]), "removed with the chat")
            val chats = repo.getPrivateChats().keys
            assertEquals(chats, alias.getAll().keys, "what is kept belongs to a chat that is there")
            assertEquals(chats, conversation.getAll().keys)

            relay(giftWrap("wrap-2", sender, "second"))
            assertEquals(listOf(sender, GEOHASH), listOf(alias[chat], conversation[chat]), "and back with its next message")
            assertEquals(sender, repo.getFullPubkey(chat))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aNostrMessageThatOpensNoChatLeavesNoKeyBehind() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val subscriptions = mutableListOf<Subscription>()
        try {
            val repo = chatRepo(scope, dispatcher, subscriptions, nostrIdentity = ME)
            val relay = subscriptions.single { it.id == NostrSubscriptionId.directMessages(ME.publicKeyHex) }.handler
            val sender = nostrKey(0)

            // Handled as a favourite notification: it is not a chat message.
            relay(giftWrap("wrap-1", sender, "[FAVORITED]:npub1someone"))

            assertEquals(emptyMap(), repo.getPrivateChats())
            assertEquals(null, repo.getFullPubkey(chatOf(sender)))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun nostrChatsFilledAndEmptiedFromManyThreadsAllKeepTheirKey() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val subscriptions = mutableListOf<Subscription>()
        val limits = MessageLimits(maxMessagesPerChat = 10, maxCharsPerChat = 10_000, maxStrangerMessages = 4)
        val repo = chatRepo(scope, dispatcher, subscriptions, messageLimits = limits, nostrIdentity = ME)
        val relay = subscriptions.single { it.id == NostrSubscriptionId.directMessages(ME.publicKeyHex) }.handler
        val threads = 8
        val pool = Executors.newFixedThreadPool(threads)
        val begin = CountDownLatch(1)
        val failures = AtomicInteger()
        try {
            repeat(threads) { thread ->
                pool.execute {
                    begin.await()
                    try {
                        // Twelve keys shared by all threads against a budget of four messages: every
                        // arrival removes some other key's chat while that key's next message is on its way.
                        repeat(200) { index -> relay(giftWrap("wrap-$thread-$index", nostrKey((thread + index) % 12), "hi")) }
                    } catch (e: Throwable) {
                        failures.incrementAndGet()
                    }
                }
            }
            begin.countDown()
            pool.shutdown()
            assertTrue(pool.awaitTermination(120, TimeUnit.SECONDS), "the handlers did not finish")

            assertEquals(0, failures.get(), "a handler failed while another was adding")
            val chats = repo.getPrivateChats()
            assertTrue(chats.isNotEmpty() && chats.values.sumOf { it.size } <= 4)
            for (chat in chats.keys) {
                assertTrue(repo.getFullPubkey(chat) != null, "$chat is there and cannot be answered")
            }
        } finally {
            pool.shutdownNow()
            scope.cancel()
        }
    }

    @Test
    fun theChatTheUserHasOpenKeepsItsPlaceAndItsKeyWhenItsMessagesGo() = withRepo(
        MessageLimits(maxMessagesPerChat = 10, maxCharsPerChat = 1_000, maxStrangerMessages = 4),
    ) { repo, _ ->
        // Opened from the people list, not written in yet.
        repo.storePersonDataForDM("nostr_open", "ab".repeat(32), sourceGeohash = null, displayName = "someone")
        repo.setSelectedChannel(Channel.MeshDM("nostr_open"))
        repeat(3) { index -> repo.didReceiveAuthenticatedPrivateMessage(privateMessage("nostr_open", "o-$index", index.toLong())) }

        repeat(8) { index -> repo.didReceiveAuthenticatedPrivateMessage(privateMessage("stranger-$index", "s-$index", 10L + index)) }

        val chats = repo.getPrivateChats()
        assertEquals(emptyList(), chats.getValue("nostr_open"), "as the largest it lost its messages, and it is still a chat")
        assertEquals("ab".repeat(32), repo.getFullPubkey("nostr_open"), "the first message written in it still has a key to go to")
        assertEquals(4, chats.values.sumOf { it.size })
    }

    @Test
    fun aChatTheUserHasWrittenInIsOutsideTheStrangersBudget() = withRepo(
        MessageLimits(maxMessagesPerChat = 10, maxCharsPerChat = 1_000, maxStrangerMessages = 8),
    ) { repo, mesh ->
        every { mesh.getPeerInfo("friend") } returns mockk<PeerInfo>(relaxed = true).also { every { it.isConnected } returns true }
        every { mesh.hasEstablishedSession("friend") } returns true
        repo.sendMessage("hello", Channel.MeshDM("friend"), "me", BitchatMessageType.Message)
        repeat(6) { index -> repo.didReceiveAuthenticatedPrivateMessage(privateMessage("friend", "f-$index", 10L + index)) }
        assertEquals(7, repo.getPrivateChats().getValue("friend").size)

        // Strangers pass their budget; the chat the user wrote in holds seven messages and is not "the largest".
        repeat(9) { index -> repo.didReceiveAuthenticatedPrivateMessage(privateMessage("stranger-$index", "s-$index", 100L + index)) }

        val chats = repo.getPrivateChats()
        assertEquals(7, chats.getValue("friend").size, "nothing a stranger sends costs this chat a message")
        assertEquals(8, chats.filterKeys { it != "friend" }.values.sumOf { it.size }, "and its messages do not count toward theirs")
    }

    @Test
    fun aPrivateChatKeepsOnlyTheNewestMessagesWhateverTheirSender() = withRepo(smallLimits) { repo, mesh ->
        every { mesh.getPeerInfo("friend") } returns mockk<PeerInfo>(relaxed = true).also { every { it.isConnected } returns true }
        every { mesh.hasEstablishedSession("friend") } returns true
        // The user's own message is the oldest; five from the friend follow, dated after it.
        repo.sendMessage("mine", Channel.MeshDM("friend"), "me", BitchatMessageType.Message)
        val later = Clock.System.now().epochSeconds + 1
        repeat(5) { index -> repo.didReceiveAuthenticatedPrivateMessage(privateMessage("friend", "f-$index", later)) }

        assertEquals((0..4).map { "f-$it" }, repo.getPrivateChats().getValue("friend").map { it.id })
    }

    @Test
    fun theUsersOwnMeshMessageIsDroppedLikeAnyOtherOnceItIsTheOldest() = withRepo(smallLimits) { repo, _ ->
        repo.sendMessage("mine", Channel.Mesh, "me", BitchatMessageType.Message)
        assertEquals(listOf("mine"), repo.getMeshMessages().map { it.content })

        repeat(5) { index -> repo.didReceiveMessage(message("mesh-$index", "theirs", index.toLong())) }

        assertEquals((0..4).map { "mesh-$it" }, repo.getMeshMessages().map { it.id })
    }

    @Test
    fun anIncomingMessageLongerThanTheLimitIsNotStoredAndOneAtTheLimitIs() = withRepo(MessageLimits()) { repo, _ ->
        val limit = BitchatMessage.MAX_CONTENT_CHARS

        repo.didReceiveMessage(message("too-long", "x".repeat(limit + 1), 0))
        repo.didReceiveAuthenticatedPrivateMessage(privateMessage("peer", "too-long-private", 0, "x".repeat(limit + 1)))
        repo.addNamedChannelMessage("test", message("too-long-named", "x".repeat(limit + 1), 0))
        assertTrue(repo.getMeshMessages().isEmpty())
        assertTrue(repo.getPrivateChats().isEmpty(), "a refused message does not even open a chat")
        assertTrue(repo.getUnreadPrivatePeers().isEmpty())
        assertTrue(repo.getNamedChannelMessages("test").isEmpty())

        repo.didReceiveMessage(message("at-limit", "x".repeat(limit), 0))
        assertEquals(listOf("at-limit"), repo.getMeshMessages().map { it.id })
    }

    @Test
    fun everySendEntryRefusesAMessageLongerThanTheLimitBeforeAnythingIsStored() = withRepo(MessageLimits()) { repo, mesh ->
        val tooLong = "x".repeat(BitchatMessage.MAX_CONTENT_CHARS + 1)
        val expected = "message is longer than ${BitchatMessage.MAX_CONTENT_CHARS} characters"

        val failures = listOf(
            assertFailsWith<IllegalArgumentException> { repo.sendMessage(tooLong, Channel.Mesh, "me", BitchatMessageType.Message) },
            assertFailsWith<IllegalArgumentException> { repo.sendMessage(tooLong, Channel.MeshDM("friend"), "me", BitchatMessageType.Message) },
            assertFailsWith<IllegalArgumentException> { repo.sendMeshMessage(tooLong, "me") },
            assertFailsWith<IllegalArgumentException> { repo.sendPrivate(tooLong, "friend", "friend") },
            assertFailsWith<IllegalArgumentException> { repo.sendGeohashMessage(tooLong, "9q8yy", "me") },
        )

        for (failure in failures) assertEquals(expected, failure.message)
        assertTrue(repo.getMeshMessages().isEmpty())
        assertTrue(repo.getPrivateChats().isEmpty())
        verify(exactly = 0) { mesh.sendMessage(any(), any()) }
        verify(exactly = 0) { mesh.sendPrivateMessage(any(), any(), any(), any()) }
    }

    @Test
    fun aMessageOfExactlyTheLimitIsSent() = withRepo(MessageLimits()) { repo, _ ->
        repo.sendMessage("x".repeat(BitchatMessage.MAX_CONTENT_CHARS), Channel.Mesh, "me", BitchatMessageType.Message)

        assertEquals(1, repo.getMeshMessages().size)
    }

    private fun withRepo(
        limits: MessageLimits,
        block: suspend TestScope.(ChatRepo, BluetoothMeshService) -> Unit,
    ) = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val mesh = mockk<BluetoothMeshService>(relaxed = true)
        try {
            block(chatRepo(scope, dispatcher, mutableListOf(), mesh = mesh, messageLimits = limits), mesh)
        } finally {
            scope.cancel()
        }
    }

    private fun privateMessage(peer: String, id: String, timestamp: Long, content: String = "hi") = BitchatMessage(
        id = id,
        sender = peer,
        senderPeerID = peer,
        content = content,
        timestamp = Instant.fromEpochSeconds(timestamp),
        isPrivate = true,
    )

    private fun nostrKey(index: Int) = index.toString(16).padStart(2, '0').repeat(32)

    private fun chatOf(pubkeyHex: String) = "nostr_${pubkeyHex.take(16)}"

    /** A gift wrap as the harness's client "decrypts" it: the embedded private message, as it is. */
    private fun giftWrap(id: String, senderPubkey: String, text: String) = NostrEvent(
        id = id,
        pubkey = senderPubkey,
        // Dated now: a wrap older than two days is refused before it is opened.
        createdAt = Clock.System.now().epochSeconds.toInt(),
        kind = NostrKind.GIFT_WRAP,
        tags = emptyList(),
        content = NostrEmbeddedBitChat.encodePMForNostr(
            content = text,
            messageID = "message-of-$id",
            recipientPeerID = "0102030405060708",
            senderPeerID = "1112131415161718",
        )!!,
    )

    private fun geohashEvent(id: String, createdAt: Int, content: String = "hi") = NostrEvent(
        id = id,
        pubkey = "key-of-$id".padEnd(64, '0'),
        createdAt = createdAt,
        kind = NostrKind.EPHEMERAL_EVENT,
        tags = listOf(listOf("g", GEOHASH), listOf("n", "someone")),
        content = content,
    )

    private fun message(id: String, content: String, timestamp: Long) = BitchatMessage(
        id = id,
        sender = "peer",
        senderPeerID = "peer",
        content = content,
        timestamp = Instant.fromEpochSeconds(timestamp),
    )

    private class FractionalClock : Clock {
        private var current = Instant.fromEpochSeconds(1_000)
        override fun now(): Instant = current
        fun advanceMillis(millis: Long) { current += millis.milliseconds }
    }

    private class TestClock : Clock {
        private var current = Instant.fromEpochSeconds(1_000)
        override fun now(): Instant = current
        fun advance() { current += 1.seconds }
    }

    private companion object {
        const val GEOHASH = "9q8yy"
        val ME = NostrIdentity(privateKeyHex = "a1".repeat(32), publicKeyHex = "b2".repeat(32), npub = "npub1me", createdAt = 0)
    }
}
