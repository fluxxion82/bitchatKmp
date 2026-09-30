package com.bitchat.domain.chat

import com.bitchat.domain.chat.model.BitchatMessage
import com.bitchat.domain.chat.model.BitchatMessageType
import com.bitchat.domain.location.model.Channel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

class ChatNoticesTest {
    private val notices = ChatNotices(limit = 3)
    private val test = Channel.NamedChannel("#test")

    private fun line(text: String, seconds: Long) = BitchatMessage(
        id = text, sender = "system", content = text, type = BitchatMessageType.System,
        timestamp = Instant.fromEpochSeconds(seconds),
    )

    private fun message(text: String, seconds: Long) =
        BitchatMessage(id = text, sender = "bob", content = text, timestamp = Instant.fromEpochSeconds(seconds))

    @Test fun linesAreKeptPerConversationWhateverDescribesIt() = runTest {
        notices.add(test, line("joined channel #test", 10))
        notices.add(Channel.Mesh, line("peers: bob", 11))
        assertEquals(listOf("joined channel #test"), notices.of(Channel.NamedChannel("Test")).map { it.message.content })
        assertEquals(listOf("peers: bob"), notices.of(Channel.Mesh).map { it.message.content })
        notices.clear(Channel.NamedChannel("#TEST"))
        assertEquals(emptyList(), notices.of(test))
        assertEquals(listOf("peers: bob"), notices.of(Channel.Mesh).map { it.message.content })
    }

    @Test fun onlyTheNewestLinesAreKeptPerConversation() = runTest {
        repeat(5) { notices.add(test, line("line $it", it.toLong())) }
        assertEquals(listOf("line 2", "line 3", "line 4"), notices.of(test).map { it.message.content })
    }

    @Test fun onlyTheMostRecentlyUsedConversationsAreKept() = runTest {
        val few = ChatNotices(limit = 3, conversations = 2)
        few.add(Channel.NamedChannel("#a"), line("in a", 1))
        few.add(Channel.NamedChannel("#b"), line("in b", 2))
        few.add(Channel.NamedChannel("#a"), line("in a again", 3)) // #a is the recent one again
        few.add(Channel.NamedChannel("#c"), line("in c", 4))
        assertEquals(emptyList(), few.of(Channel.NamedChannel("#b")), "the least recently used goes")
        assertEquals(listOf("in a", "in a again"), few.of(Channel.NamedChannel("#a")).map { it.message.content })
        assertEquals(listOf("in c"), few.of(Channel.NamedChannel("#c")).map { it.message.content })
    }

    @Test fun wipingTheAppSDataDropsEveryLine() = runTest {
        notices.add(test, line("joined channel #test", 1))
        notices.add(Channel.Mesh, line("peers: bob", 2))
        notices.reset {}
        assertEquals(emptyList(), notices.of(test))
        assertEquals(emptyList(), notices.of(Channel.Mesh))
    }

    @Test fun aLineFromWorkThatBeganBeforeAWipeIsNotFiled() = runTest {
        val before = notices.epoch() // A command running while the user wipes their data.
        notices.reset {}
        withContext(before) { notices.add(test, line("peers: bob", 10)) }
        assertEquals(emptyList(), notices.of(test), "the old identity's peers stay out of the new one")
        withContext(notices.epoch()) { notices.add(test, line("peers: carol", 11)) }
        assertEquals(listOf("peers: carol"), notices.of(test).map { it.message.content })
    }

    @Test fun aLineFromWorkThatBeganInsideAWipeIsNotFiledEither() = runTest {
        // The stores are cleared one by one, so work that starts here reads some of them before
        // the wipe and some after. Nothing it learned belongs to either identity.
        var inside: ChatNotices.Epoch? = null
        notices.reset {
            assertEquals(true, notices.resetting, "no command may start while this runs")
            inside = notices.epoch()
        }
        assertEquals(false, notices.resetting)
        withContext(inside!!) { notices.add(test, line("peers: bob", 10)) }
        assertEquals(emptyList(), notices.of(test))
    }

    @Test fun theFirstOfTwoWipesToFinishDoesNotAdmitWorkIntoTheSecond() = runTest {
        // Two triple clicks: whichever wipe ends first must not reopen admission while the other
        // is still clearing stores.
        val firstClearing = CompletableDeferred<Unit>()
        val letFirstFinish = CompletableDeferred<Unit>()
        val secondClearing = CompletableDeferred<Unit>()
        val letSecondFinish = CompletableDeferred<Unit>()
        val first = launch { notices.reset { firstClearing.complete(Unit); letFirstFinish.await() } }
        firstClearing.await()
        val second = launch { notices.reset { secondClearing.complete(Unit); letSecondFinish.await() } }

        letFirstFinish.complete(Unit)
        first.join()
        secondClearing.await() // The second is inside its own clearing now, whenever it got there.
        assertEquals(true, notices.resetting, "no command may start while the second one clears")

        letSecondFinish.complete(Unit)
        second.join()
        assertEquals(false, notices.resetting, "open again once the last one is done")
    }

    @Test fun aWipeThatFailsPartWayStillLetsTheAppGoOn() = runTest {
        val failed = runCatching { notices.reset { error("the keystore is gone") } }
        assertEquals(true, failed.isFailure)
        assertEquals(false, notices.resetting, "commands are admitted again")
        withContext(notices.epoch()) { notices.add(test, line("peers: bob", 10)) }
        assertEquals(listOf("peers: bob"), notices.of(test).map { it.message.content })
    }

    @Test fun linesKeepTheirPlaceAmongTheMessages() {
        val merged = mergeNotices(
            listOf(message("a", 1), message("c", 3), message("b", 2)),
            listOf(Notice(line("n2", 2), Instant.fromEpochSeconds(2)), Notice(line("n0", 0), Instant.fromEpochSeconds(0))),
        )
        // The messages keep their own order (even out of time order); each line goes before the first later message.
        assertEquals(listOf("n0", "a", "n2", "c", "b"), merged.map { it.content })
    }

    @Test fun aMessageDatedInTheFutureDoesNotBuryFreshFeedback() = runTest {
        val future = message("from 2030", 2_000_000_000)
        notices.add(test, line("peers: bob", 10), notBefore = future.timestamp)
        assertEquals(
            listOf("from 2030", "peers: bob"),
            mergeNotices(listOf(future), notices.of(test)).map { it.content },
        )
    }
}
