package com.bitchat.viewmodel.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HugCommandTest {
    @Test
    fun buildsActionMessageForHugCommand() {
        val result = buildHugActionMessage("/hug @anon6230#3098", "anon3484")

        assertEquals("* anon3484 gives anon6230#3098 a warm hug 🫂 *", result)
    }

    @Test
    fun returnsNullWhenHugTargetMissing() {
        assertNull(buildHugActionMessage("/hug", "anon"))
        assertNull(buildHugActionMessage("/hug   ", "anon"))
    }
}
