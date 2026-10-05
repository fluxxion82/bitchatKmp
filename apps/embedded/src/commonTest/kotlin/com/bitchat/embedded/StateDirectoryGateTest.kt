package com.bitchat.embedded

import com.bitchat.local.statedir.StateDirectoryException
import kotlin.test.Test
import kotlin.test.assertEquals

class StateDirectoryGateTest {
    @Test
    fun refusalPrintsOneLineAndExitsWithConfigurationStatus() {
        val lines = mutableListOf<String>()
        val statuses = mutableListOf<Int>()

        StateDirectoryGate.requireOrExit(
            "bitchat-tui",
            ensure = { throw StateDirectoryException("state directory is unsafe") },
            stderr = { lines += it },
            exit = { statuses += it },
        )

        assertEquals(listOf("bitchat-tui: state directory is unsafe; not starting"), lines)
        assertEquals(listOf(78), statuses)
    }

    @Test
    fun acceptedDirectoryPrintsNothingAndDoesNotExit() {
        val lines = mutableListOf<String>()
        val statuses = mutableListOf<Int>()

        StateDirectoryGate.requireOrExit(
            "bitchat-tui",
            ensure = { "/home/u/.bitchat" },
            stderr = { lines += it },
            exit = { statuses += it },
        )

        assertEquals(emptyList(), lines)
        assertEquals(emptyList(), statuses)
    }
}
