package com.bitchat.bluetooth.manager

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HandshakeSupervisorTest {

    private val timeout = 10_000L

    private fun supervisor(
        maxAttempts: Int = 5,
        maxBackoffMs: Long = 120_000L
    ) = HandshakeSupervisor(timeoutMs = timeout, maxAttempts = maxAttempts, maxBackoffMs = maxBackoffMs)

    @Test
    fun handshakeIsNotExpiredBeforeTheDeadline() {
        val supervisor = supervisor()
        val startedAt = 1_000L

        assertFalse(supervisor.isExpired(startedAt, startedAt))
        assertFalse(supervisor.isExpired(startedAt, startedAt + timeout - 1))
    }

    @Test
    fun handshakeIsExpiredOnceTheDeadlinePasses() {
        val supervisor = supervisor()
        val startedAt = 1_000L

        assertTrue(supervisor.isExpired(startedAt, startedAt + timeout))
        assertTrue(supervisor.isExpired(startedAt, startedAt + timeout * 10))
    }

    @Test
    fun aPeerWeHaveNeverTriedMayAlwaysBeAttempted() {
        assertTrue(supervisor().mayAttempt("peer", now = 0L))
    }

    @Test
    fun theFirstRetryIsAllowedAsSoonAsTheHandshakeExpires() {
        val supervisor = supervisor()
        val startedAt = 1_000L
        supervisor.recordAttempt("peer", startedAt)

        assertFalse(supervisor.mayAttempt("peer", startedAt + timeout - 1))
        assertTrue(supervisor.mayAttempt("peer", startedAt + timeout))
    }

    @Test
    fun backoffDoublesAndIsCapped() {
        val supervisor = supervisor(maxBackoffMs = 40_000L)

        assertEquals(0L, supervisor.backoffFor(0))
        assertEquals(10_000L, supervisor.backoffFor(1))
        assertEquals(20_000L, supervisor.backoffFor(2))
        assertEquals(40_000L, supervisor.backoffFor(3))
        assertEquals(40_000L, supervisor.backoffFor(4))
        assertEquals(40_000L, supervisor.backoffFor(9))
    }

    @Test
    fun repeatedFailuresBackOffRatherThanStorming() {
        val supervisor = supervisor()
        var now = 0L
        val attemptTimes = mutableListOf<Long>()

        // Drive the loop the sweeper runs: every second, retry whenever the budget allows.
        repeat(400) {
            if (supervisor.mayAttempt("peer", now) && !supervisor.isExhausted("peer")) {
                supervisor.recordAttempt("peer", now)
                attemptTimes.add(now)
            }
            now += 1_000L
        }

        assertEquals(listOf(0L, 10_000L, 30_000L, 70_000L, 150_000L), attemptTimes)
        assertTrue(supervisor.isExhausted("peer"))
        assertFalse(supervisor.mayAttempt("peer", now))
    }

    @Test
    fun theBudgetIsSpentAfterMaxAttempts() {
        val supervisor = supervisor(maxAttempts = 2)

        supervisor.recordAttempt("peer", 0L)
        supervisor.recordAttempt("peer", 100_000L)

        assertTrue(supervisor.isExhausted("peer"))
        assertFalse(supervisor.mayAttempt("peer", 10_000_000L))
    }

    @Test
    fun resetGivesAReturningPeerAFreshBudget() {
        val supervisor = supervisor(maxAttempts = 2)
        supervisor.recordAttempt("peer", 0L)
        supervisor.recordAttempt("peer", 100_000L)
        assertFalse(supervisor.mayAttempt("peer", 200_000L))

        supervisor.reset("peer")

        assertEquals(0, supervisor.attemptsFor("peer"))
        assertFalse(supervisor.isExhausted("peer"))
        assertTrue(supervisor.mayAttempt("peer", 200_000L))
    }

    @Test
    fun budgetsAreTrackedPerPeer() {
        val supervisor = supervisor(maxAttempts = 1)
        supervisor.recordAttempt("a", 0L)

        assertTrue(supervisor.isExhausted("a"))
        assertFalse(supervisor.isExhausted("b"))
        assertTrue(supervisor.mayAttempt("b", 0L))
    }
}
