package com.bitchat.domain.app

import com.bitchat.domain.chat.ChatNotices
import com.bitchat.domain.chat.model.BitchatMessage
import com.bitchat.domain.chat.model.BitchatMessageType
import com.bitchat.domain.location.model.Channel
import com.bitchat.domain.user.repository.UserRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

class ClearAllDataTest {
    private val notices = ChatNotices()

    private fun line(text: String) = BitchatMessage(
        id = text, sender = "system", content = text, type = BitchatMessageType.System,
        timestamp = Instant.fromEpochSeconds(1),
    )

    /** [whileClearing] runs in the middle of the wipe, after the first store has been cleared. */
    private fun wipe(whileClearing: suspend () -> Unit = {}) = ClearAllData(
        chatRepository = mockk(relaxed = true),
        chatNotices = notices,
        userRepository = mockk<UserRepository>(relaxed = true) { coEvery { clearData() } coAnswers { whileClearing() } },
        appRepository = mockk(relaxed = true),
        locationRepository = mockk(relaxed = true),
        blockListRepository = mockk(relaxed = true),
        nostrRepository = mockk(relaxed = true),
        torRepository = mockk(relaxed = true),
        userEventBus = mockk(relaxed = true),
    )

    @Test fun wipingTheDataTakesTheCommandFeedbackWithIt() = runTest {
        // The lines name peers and channels, so they must not greet the identity that comes next.
        notices.add(Channel.Mesh, line("peers: bob"))
        wipe()(Unit)
        assertEquals(emptyList(), notices.of(Channel.Mesh))
    }

    @Test fun theWipeIsABarrierAroundEveryStoreItClears() = runTest {
        var startedInside: ChatNotices.Epoch? = null
        wipe {
            // Half the stores still hold the old identity's data, so nothing may start here.
            assertEquals(true, notices.resetting)
            startedInside = notices.epoch()
            notices.add(Channel.Mesh, line("peers: bob"))
        }(Unit)
        assertEquals(false, notices.resetting, "and work is admitted again afterwards")
        assertEquals(emptyList(), notices.of(Channel.Mesh))
        // What began inside the wipe read stores that were half cleared: it files nothing either.
        withContext(startedInside!!) { notices.add(Channel.Mesh, line("peers: carol")) }
        assertEquals(emptyList(), notices.of(Channel.Mesh))
    }
}
