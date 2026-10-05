package com.bitchat.desktop.tui

import kotlin.test.Test
import kotlin.test.assertEquals

class RunGuardedTest {
    @Test
    fun exceptionAfterStartingANonDaemonThreadReportsOnceAndExitsOne() {
        val reports = mutableListOf<String>()
        val exits = mutableListOf<Int>()

        runGuarded(
            block = {
                Thread { }.apply { isDaemon = false }.start()
                error("boom")
            },
            report = { reports += it.message.orEmpty() },
            exit = { exits += it },
        )

        assertEquals(listOf("boom"), reports)
        assertEquals(listOf(1), exits)
    }

    @Test
    fun normalReturnExitsZero() {
        val exits = mutableListOf<Int>()
        runGuarded(block = {}, report = { error("unexpected report") }, exit = { exits += it })
        assertEquals(listOf(0), exits)
    }
}
