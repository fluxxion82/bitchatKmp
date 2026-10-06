package com.bitchat.repo.utils

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReceivedFileBudgetTest {
    private val minimum = ReceivedFileBudget.MINIMUM_CHARGE_BYTES

    @Test
    fun aSmallFileIsChargedTheMinimumAndTheLimitIsNeverPassed() {
        val budget = ReceivedFileBudget(limitBytes = 2L * minimum)

        assertTrue(budget.reserve(1))
        assertTrue(budget.reserve(1))
        assertFalse(budget.reserve(1), "two one-byte files have used two minimum charges")
        assertFalse(budget.reserve(0))
    }

    @Test
    fun aFileIsChargedItsSizeWhenThatIsMoreThanTheMinimum() {
        val budget = ReceivedFileBudget(limitBytes = 10L * minimum)

        assertTrue(budget.reserve(9 * minimum))
        assertFalse(budget.reserve(minimum + 1), "only one minimum charge is left")
        assertTrue(budget.reserve(minimum))
        assertFalse(budget.reserve(1))
    }

    @Test
    fun aFileThatDoesNotFitChargesNothing() {
        val budget = ReceivedFileBudget(limitBytes = 3L * minimum)

        assertFalse(budget.reserve(4 * minimum))
        assertTrue(budget.reserve(3 * minimum), "the refused file left the whole budget")
    }

    @Test
    fun reservationsFromManyThreadsNeverPassTheLimit() {
        val budget = ReceivedFileBudget(limitBytes = 100L * minimum)
        val granted = AtomicInteger()
        val threads = 8
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        try {
            repeat(threads) {
                pool.execute {
                    start.await()
                    repeat(100) { if (budget.reserve(1)) granted.incrementAndGet() }
                }
            }
            start.countDown()
            pool.shutdown()
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "the reservations did not finish")
        } finally {
            pool.shutdownNow()
        }

        assertEquals(100, granted.get(), "800 attempts at a budget of 100 minimum charges")
        assertFalse(budget.reserve(1))
    }
}
