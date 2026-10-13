package com.bitchat.bluetooth.manager

import com.bitchat.bluetooth.manager.DialBudget.Standing.LINKED
import com.bitchat.bluetooth.manager.DialBudget.Standing.FAILING
import com.bitchat.bluetooth.manager.DialBudget.Standing.UNTRIED
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The amount of dial time a board may use, whatever the number of addresses around it.
 *
 * The numbers here are small on purpose: 100 ms of allowance, a sixth of elapsed time regained, 30 ms
 * that only an address that has linked before may use, 60 ms that an address which has only ever
 * failed may not. A millisecond of dialling takes a millisecond and gives a sixth back, so 120 ms of
 * dialling uses up a full allowance exactly.
 */
class DialBudgetTest {

    private fun budget() = DialBudget(
        capacityMs = 100L,
        refillDivisor = 6L,
        untriedReserveMs = 30L,
        failingReserveMs = 60L
    )

    @Test
    fun itStartsFull() {
        val budget = budget()

        assertEquals(100L, budget.balanceMs())
        assertTrue(budget.allows(LINKED))
        assertTrue(budget.allows(UNTRIED))
        assertTrue(budget.allows(FAILING))
    }

    @Test
    fun timeBeforeTheFirstSettleIsNotCounted() {
        val budget = budget()

        // Whatever the clock says when the policy is first asked, nothing has been dialled yet.
        budget.settle(now = 1_000_000L, dialling = true)

        assertEquals(100L, budget.balanceMs())
    }

    @Test
    fun aDialCostsItsLengthLessTheSixthThatComesBack() {
        val budget = budget()

        budget.settle(now = 0L, dialling = false)
        budget.settle(now = 60L, dialling = true)

        assertEquals(50L, budget.balanceMs())
    }

    @Test
    fun timeWithoutADialGivesASixthBack() {
        val budget = budget()
        budget.settle(now = 0L, dialling = false)
        budget.settle(now = 60L, dialling = true)

        budget.settle(now = 120L, dialling = false)

        assertEquals(60L, budget.balanceMs())
    }

    @Test
    fun itNeverHoldsMoreThanItsCapacity() {
        val budget = budget()
        budget.settle(now = 0L, dialling = false)
        budget.settle(now = 60L, dialling = true)

        budget.settle(now = 1_000_000L, dialling = false)

        assertEquals(100L, budget.balanceMs())
    }

    @Test
    fun anAddressThatHasLinkedBeforeNeedsSomethingLeft() {
        val budget = budget()
        budget.settle(now = 0L, dialling = false)

        budget.settle(now = 120L, dialling = true)
        assertEquals(0L, budget.balanceMs())
        assertFalse(budget.allows(LINKED))

        // One millisecond later a sixth of a millisecond is back, and that is enough.
        budget.settle(now = 121L, dialling = false)
        assertTrue(budget.allows(LINKED))
        assertFalse(budget.allows(UNTRIED))
    }

    @Test
    fun anAddressNeverTriedNeedsMoreThanWhatIsKeptForThoseThatHaveLinked() {
        val budget = budget()
        budget.settle(now = 0L, dialling = false)

        budget.settle(now = 84L, dialling = true)
        assertEquals(30L, budget.balanceMs())
        assertFalse(budget.allows(UNTRIED))
        assertTrue(budget.allows(LINKED))

        budget.settle(now = 85L, dialling = false)
        assertTrue(budget.allows(UNTRIED))
        assertFalse(budget.allows(FAILING))
    }

    @Test
    fun anAddressThatHasOnlyFailedNeedsMoreThanWhatIsKeptForFirstTries() {
        val budget = budget()
        budget.settle(now = 0L, dialling = false)

        budget.settle(now = 48L, dialling = true)
        assertEquals(60L, budget.balanceMs())
        assertFalse(budget.allows(FAILING))
        assertTrue(budget.allows(UNTRIED))

        budget.settle(now = 49L, dialling = false)
        assertTrue(budget.allows(FAILING))
    }

    @Test
    fun anOverdrawnBudgetAllowsNothingUntilItIsPaidBack() {
        val budget = budget()
        budget.settle(now = 0L, dialling = false)

        budget.settle(now = 144L, dialling = true)
        assertEquals(-20L, budget.balanceMs())
        assertFalse(budget.allows(LINKED))
        assertFalse(budget.allows(UNTRIED))
        assertFalse(budget.allows(FAILING))

        // 20 ms of debt is paid back in 120 ms, and not before. A sixth of a millisecond short
        // still reads as short.
        budget.settle(now = 263L, dialling = false)
        assertEquals(-1L, budget.balanceMs())
        budget.settle(now = 264L, dialling = false)
        assertEquals(0L, budget.balanceMs())
        assertFalse(budget.allows(LINKED))
        budget.settle(now = 265L, dialling = false)
        assertTrue(budget.allows(LINKED))
    }

    @Test
    fun aDialThatNeverEndsOwesNoMoreThanOneAllowance() {
        val budget = budget()
        budget.settle(now = 0L, dialling = false)

        budget.settle(now = 1_000_000L, dialling = true)

        assertEquals(-100L, budget.balanceMs())

        // One allowance of debt exactly: 600 ms pay it back, and not a millisecond less.
        budget.settle(now = 1_000_600L, dialling = false)
        assertFalse(budget.allows(LINKED))
        budget.settle(now = 1_000_601L, dialling = false)
        assertTrue(budget.allows(LINKED))
    }

    @Test
    fun aClockThatGoesBackChangesNothingAndCountingGoesOnFromThere() {
        val budget = budget()
        budget.settle(now = 1_000L, dialling = false)
        budget.settle(now = 1_060L, dialling = true)
        assertEquals(50L, budget.balanceMs())

        budget.settle(now = 400L, dialling = true)
        assertEquals(50L, budget.balanceMs())

        // Counted from 400, not from 1,060: a board whose clock was set back an hour must not go
        // an hour without regaining anything.
        budget.settle(now = 460L, dialling = false)
        assertEquals(60L, budget.balanceMs())
    }

    @Test
    fun settlingEveryMillisecondComesToTheSameAsSettlingOnce() {
        val often = budget()
        val once = budget()
        often.settle(now = 0L, dialling = false)
        once.settle(now = 0L, dialling = false)

        // 60 ms of dialling, then 31 ms without: a sixth of a millisecond at a time must not be
        // rounded away, or a policy asked every few milliseconds would never regain anything.
        (1L..60L).forEach { often.settle(now = it, dialling = true) }
        (61L..91L).forEach { often.settle(now = it, dialling = false) }
        once.settle(now = 60L, dialling = true)
        once.settle(now = 91L, dialling = false)

        assertEquals(55L, once.balanceMs())
        assertEquals(once.balanceMs(), often.balanceMs())
        (92L..96L).forEach { often.settle(now = it, dialling = false) }
        assertEquals(56L, often.balanceMs())
    }

    @Test
    fun theStatusLineShowsWholeSecondsAndAnyDebtAsDebt() {
        assertEquals("57s", dialTimeLeftText(57_900L))
        assertEquals("1s", dialTimeLeftText(1_000L))
        assertEquals("0s", dialTimeLeftText(999L))
        assertEquals("0s", dialTimeLeftText(0L))
        assertEquals("-1s", dialTimeLeftText(-300L))
        assertEquals("-13s", dialTimeLeftText(-12_300L))
    }

    @Test
    fun theBoardsNumbers() {
        assertEquals(90_000L, DialBudget.CAPACITY_MS)
        assertEquals(6L, DialBudget.REFILL_DIVISOR)
        assertEquals(30_000L, DialBudget.UNTRIED_RESERVE_MS)
        assertEquals(60_000L, DialBudget.FAILING_RESERVE_MS)
    }

    @Test
    fun aFullAllowanceCoversARedialAndThreeRetriesToAPeerThatHasLinkedBefore() {
        // 25 s each, 5, 10 and 20 s apart: what a board was seen to need to get a dropped link back.
        val budget = DialBudget()
        var now = 0L
        budget.settle(now, dialling = false)
        assertTrue(budget.allows(LINKED))
        listOf(5_000L, 10_000L, 20_000L).forEach { pause ->
            now += 25_000L
            budget.settle(now, dialling = true)
            now += pause
            budget.settle(now, dialling = false)
            assertTrue(budget.allows(LINKED), "retry after a pause of $pause ms")
        }
    }

    @Test
    fun anAddressThatHasNeverLinkedCannotTakeTheLastOfIt() {
        // It may start with just over 30 s left. The longest dial seen, 34 s, and two seconds more
        // still leave something for a peer that has linked before.
        val budget = DialBudget()
        budget.settle(now = 0L, dialling = false)
        budget.settle(now = 72_000L, dialling = true)
        budget.settle(now = 72_001L, dialling = false)
        assertTrue(budget.allows(UNTRIED))

        budget.settle(now = 72_001L + 36_000L, dialling = true)

        assertTrue(budget.allows(LINKED))
    }
}
