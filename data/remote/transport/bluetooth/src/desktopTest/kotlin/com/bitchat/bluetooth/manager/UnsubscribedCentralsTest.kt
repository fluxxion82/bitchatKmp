package com.bitchat.bluetooth.manager

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which centrals that write to our GATT server never subscribed, and when one is asked to leave.
 * (`onSeen` is a write: a central is known by writing, see the class.)
 *
 * Seen on a Pixel 4 XL on 2026-10-08: the app was restarted while three centrals stayed connected.
 * Android handed the new process those links, the centrals kept writing to it, and nothing it
 * notified was acted on for two hours, until Bluetooth was switched off and on. A central that
 * connects afresh subscribes within a couple of seconds; one that has not after the grace is such a
 * leftover (or lost its subscription some other way) and only a new connection makes it listen.
 */
class UnsubscribedCentralsTest {

    private val grace = 15_000L
    private val again = 300_000L

    /** The tracker, and the GATT service that reports to it; [onServiceReset] is a stop and a start. */
    private class Tracked(val tracker: UnsubscribedCentrals) {
        var service = tracker.onServiceReset()

        fun onSeen(address: String, now: Long) = service.onSeen(address, now)
        fun onSubscribed(address: String) = service.onSubscribed(address)
        fun onGone(address: String) = service.onGone(address)
        fun onServiceReset() {
            service = tracker.onServiceReset()
        }

        fun due(now: Long) = tracker.due(now)
        fun named(now: Long): List<String> = due(now).map { it.address }
        fun isStillDue(due: UnsubscribedCentrals.Due) = tracker.isStillDue(due)
        fun takeIfStillDue(due: UnsubscribedCentrals.Due, now: Long) = tracker.takeIfStillDue(due, now)
        fun mayHaveSubscribed(address: String) = tracker.mayHaveSubscribed(address)
        fun refusing() = tracker.refusing()
        fun remembered() = tracker.remembered()
    }

    private fun centrals(max: Int = 64) =
        Tracked(UnsubscribedCentrals(graceMs = grace, askAgainAfterMs = again, maxTracked = max))

    @Test
    fun aCentralThatHasNotSubscribedByTheEndOfTheGraceIsDue() {
        val centrals = centrals()
        centrals.onSeen("A", now = 1_000)

        assertEquals(emptyList(), centrals.named(now = 1_000 + grace - 1))
        assertEquals(listOf("A"), centrals.named(now = 1_000 + grace))
    }

    @Test
    fun aCentralThatSubscribedIsNeverDue() {
        val centrals = centrals()
        centrals.onSeen("A", now = 0)
        centrals.onSubscribed("A")

        assertEquals(emptyList(), centrals.named(now = grace))
        assertEquals(emptyList(), centrals.named(now = grace + again * 10))
    }

    @Test
    fun aSubscriptionCountsEvenWhenItIsTheFirstThingSeenOfACentral() {
        val centrals = centrals()
        centrals.onSubscribed("A")
        centrals.onSeen("A", now = 0)

        assertEquals(emptyList(), centrals.named(now = grace))
    }

    @Test
    fun theGraceRunsFromTheFirstSightAndAWriteDoesNotStartItAgain() {
        val centrals = centrals()
        centrals.onSeen("A", now = 0)
        centrals.onSeen("A", now = grace - 1)

        assertEquals(listOf("A"), centrals.named(now = grace))
    }

    @Test
    fun aCentralThatLeftIsNotDue() {
        val centrals = centrals()
        centrals.onSeen("A", now = 0)
        centrals.onGone("A")

        assertEquals(emptyList(), centrals.named(now = grace))
    }

    @Test
    fun aCentralIsNamedOnceAndNotAgainOnTheNextLook() {
        val centrals = centrals()
        centrals.onSeen("A", now = 0)

        assertEquals(listOf("A"), centrals.named(now = grace))
        assertEquals(emptyList(), centrals.named(now = grace + 5_000))
    }

    @Test
    fun aCentralAskedToLeaveThatComesBackAndStillDoesNotSubscribeIsLeftAloneForAWhile() {
        val centrals = centrals()
        centrals.onSeen("A", now = 0)
        assertEquals(listOf("A"), centrals.named(now = grace))

        // It was disconnected, and connected again under the same address.
        centrals.onGone("A")
        centrals.onSeen("A", now = grace + 2_000)

        // Asking again every grace would be a loop of connections for a central that never subscribes.
        assertEquals(emptyList(), centrals.named(now = grace + 2_000 + grace))
        assertEquals(emptyList(), centrals.named(now = grace + again - 1))
        assertEquals(listOf("A"), centrals.named(now = grace + again))
    }

    @Test
    fun aCentralAskedToLeaveThatComesBackAndSubscribesIsNotAskedAgain() {
        val centrals = centrals()
        centrals.onSeen("A", now = 0)
        assertEquals(listOf("A"), centrals.named(now = grace))

        centrals.onGone("A")
        centrals.onSeen("A", now = grace + 2_000)
        centrals.onSubscribed("A")

        assertEquals(emptyList(), centrals.named(now = grace + again * 2))
    }

    @Test
    fun aNewConnectionOfACentralThatHadSubscribedGetsAGraceOfItsOwn() {
        val centrals = centrals()
        centrals.onSeen("A", now = 0)
        centrals.onSubscribed("A")
        centrals.onGone("A")

        // The subscription went with the connection.
        centrals.onSeen("A", now = 100_000)
        assertEquals(emptyList(), centrals.named(now = 100_000 + grace - 1))
        assertEquals(listOf("A"), centrals.named(now = 100_000 + grace))
    }

    @Test
    fun severalCentralsAreJudgedEachByItsOwnClock() {
        val centrals = centrals()
        centrals.onSeen("A", now = 0)
        centrals.onSeen("B", now = 10_000)
        centrals.onSeen("C", now = 10_000)
        centrals.onSubscribed("C")

        assertEquals(listOf("A"), centrals.named(now = grace))
        assertEquals(listOf("B"), centrals.named(now = 10_000 + grace))
    }

    @Test
    fun aSubscriptionToAServiceThatWasTakenDownDoesNotCountForTheNextOne() {
        val centrals = centrals()
        centrals.onSeen("A", now = 0)
        centrals.onSubscribed("A")

        // The service is stopped and started; the central stays connected and writes again.
        centrals.onServiceReset()
        centrals.onSeen("A", now = 50_000)

        assertEquals(emptyList(), centrals.named(now = 50_000 + grace - 1))
        assertEquals(listOf("A"), centrals.named(now = 50_000 + grace))
    }

    @Test
    fun aServiceResetDoesNotMakeACentralBeAskedAgainEarly() {
        val centrals = centrals()
        centrals.onSeen("A", now = 0)
        assertEquals(listOf("A"), centrals.named(now = grace))

        centrals.onServiceReset()
        centrals.onSeen("A", now = grace + 1_000)

        assertEquals(emptyList(), centrals.named(now = grace + 1_000 + grace))
        assertEquals(listOf("A"), centrals.named(now = grace + again))
    }

    @Test
    fun whatWasDueStillStandsAfterAWrite() {
        val centrals = centrals()
        centrals.onSeen("A", now = 0)
        val due = centrals.due(now = grace).single()

        // A write is not a subscription.
        centrals.onSeen("A", now = grace + 100)

        assertTrue(centrals.isStillDue(due))
    }

    @Test
    fun whatWasDueNoLongerStandsOnceTheCentralSubscribes() {
        val centrals = centrals()
        centrals.onSeen("A", now = 0)
        val due = centrals.due(now = grace).single()

        centrals.onSubscribed("A")

        assertFalse(centrals.isStillDue(due), "a subscription that arrived after the look was not seen")
    }

    @Test
    fun whatWasDueNoLongerStandsOnceTheCentralLeft() {
        val centrals = centrals()
        centrals.onSeen("A", now = 0)
        val due = centrals.due(now = grace).single()

        centrals.onGone("A")

        assertFalse(centrals.isStillDue(due))
    }

    @Test
    fun whatWasDueDoesNotStandForANewConnectionFromTheSameAddress() {
        val centrals = centrals()
        centrals.onSeen("A", now = 0)
        val due = centrals.due(now = grace).single()

        // The connection ended and the central is back, a fraction of a second old and not yet
        // subscribed: dropping it for what the old connection did would start the loop.
        centrals.onGone("A")
        centrals.onSeen("A", now = grace + 200)

        assertFalse(centrals.isStillDue(due))
    }

    @Test
    fun theLastLookTakesTheRecordOfTheConnectionAboutToBeDropped() {
        val centrals = centrals(max = 2)
        centrals.onSeen("A", now = 0)
        val due = centrals.due(now = grace).single()

        assertTrue(centrals.takeIfStillDue(due, now = grace))

        // Its disconnection is never reported; the record must not stay behind and fill the table.
        assertFalse(centrals.takeIfStillDue(due, now = grace), "the same connection was handed out twice")
        assertEquals(1, centrals.remembered(), "only the memory of having asked A is left")
        centrals.onSeen("B", now = grace)
        centrals.onSeen("C", now = grace)
        // Asked once A's asking is forgotten, so that the two places for asking are free again.
        assertEquals(listOf("B", "C"), centrals.named(now = grace + again))
    }

    @Test
    fun theLastLookLeavesASubscribedOrNewConnectionRecorded() {
        val centrals = centrals()
        centrals.onSeen("A", now = 0)
        val subscribedSince = centrals.due(now = grace).single()
        centrals.onSubscribed("A")
        assertFalse(centrals.takeIfStillDue(subscribedSince, now = grace))

        centrals.onSeen("B", now = 0)
        val replaced = centrals.due(now = grace).single()
        centrals.onGone("B")
        centrals.onSeen("B", now = grace + 200)
        assertFalse(centrals.takeIfStillDue(replaced, now = grace + 300))

        // Both are still known: A as subscribed (a write does not make it a newcomer), B as a new
        // connection with a grace of its own.
        centrals.onSeen("A", now = grace + 400)
        assertEquals(listOf("B"), centrals.named(now = grace + again))
    }

    @Test
    fun aCentralStillThereAfterItsLinkWasDroppedIsAskedAgainOnlyWhenItsAddressMayBe() {
        val centrals = centrals()
        centrals.onSeen("A", now = 0)
        assertTrue(centrals.takeIfStillDue(centrals.due(now = grace).single(), now = grace))

        // The drop did nothing: the central writes again.
        centrals.onSeen("A", now = grace + 1_000)

        assertEquals(emptyList(), centrals.named(now = grace + 1_000 + grace))
        assertEquals(listOf("A"), centrals.named(now = grace + again))
    }

    @Test
    fun theTimeBeforeAnAddressIsAskedAgainRunsFromTheDropNotFromTheLookThatNamedIt() {
        val centrals = centrals()
        centrals.onSeen("A", now = 0)
        val due = centrals.due(now = grace).single()

        // The drop came a minute after the look.
        val dropped = grace + 60_000
        assertTrue(centrals.takeIfStillDue(due, now = dropped))
        centrals.onSeen("A", now = dropped + 1_000)

        assertEquals(emptyList(), centrals.named(now = grace + again), "counted from the look: two drops less than a period apart")
        assertEquals(emptyList(), centrals.named(now = dropped + again - 1))
        assertEquals(listOf("A"), centrals.named(now = dropped + again))
    }

    @Test
    fun aSubscriptionReportedByAServiceThatWasTakenDownDoesNotCount() {
        val centrals = centrals()
        val takenDown = centrals.service
        centrals.onSeen("A", now = 0)

        // Stop and start; the callback of the old service arrives only now. The central stayed
        // connected and writes to the new service, to which it never subscribed.
        centrals.onServiceReset()
        takenDown.onSubscribed("A")
        centrals.onSeen("A", now = 1_000)

        assertEquals(listOf("A"), centrals.named(now = 1_000 + grace))
    }

    @Test
    fun aSightReportedByAServiceThatWasTakenDownStartsNoGrace() {
        val centrals = centrals()
        val takenDown = centrals.service
        centrals.onServiceReset()

        takenDown.onSeen("A", now = 0)
        assertEquals(emptyList(), centrals.named(now = grace), "a central no service in use has seen was named")

        // The new service sees it later, and its grace runs from then.
        centrals.onSeen("A", now = grace)
        assertEquals(emptyList(), centrals.named(now = grace + grace - 1))
        assertEquals(listOf("A"), centrals.named(now = grace + grace))
    }

    @Test
    fun aDisconnectionReportedByAServiceThatWasTakenDownCostsNoSubscribedCentralItsRecord() {
        val centrals = centrals()
        val takenDown = centrals.service
        centrals.onServiceReset()
        centrals.onSeen("A", now = 0)
        centrals.onSubscribed("A")

        // The end of an earlier link from that address, reported late by the old service.
        takenDown.onGone("A")
        centrals.onSeen("A", now = 1_000)

        assertEquals(emptyList(), centrals.named(now = 1_000 + grace))
        assertEquals(emptyList(), centrals.named(now = 1_000 + again * 2))
    }

    @Test
    fun aCentralCountsAsSubscribedOnlyOnTheConnectionAndTheServiceItSubscribedOn() {
        val centrals = centrals()
        assertFalse(centrals.mayHaveSubscribed("A"), "a central nobody has heard of")

        centrals.onSeen("A", now = 0)
        assertFalse(centrals.mayHaveSubscribed("A"), "a write is not a subscription")
        centrals.onSubscribed("A")
        assertTrue(centrals.mayHaveSubscribed("A"))

        centrals.onGone("A")
        assertFalse(centrals.mayHaveSubscribed("A"), "the subscription went with the connection")

        centrals.onSubscribed("B")
        assertTrue(centrals.mayHaveSubscribed("B"))
        centrals.onServiceReset()
        assertFalse(centrals.mayHaveSubscribed("B"), "it had subscribed to the service that was taken down")
    }

    @Test
    fun onceTheTableHasBeenFullEveryCentralMayHaveSubscribed() {
        val centrals = centrals(max = 1)
        centrals.onSeen("A", now = 0)
        assertFalse(centrals.mayHaveSubscribed("B"))

        // No room for B: its subscription is not recorded, and it is not the only thing that
        // may have gone unrecorded from here on.
        centrals.onSubscribed("B")

        assertTrue(centrals.mayHaveSubscribed("B"), "a central that subscribed and could not be recorded")
        assertTrue(centrals.mayHaveSubscribed("C"))

        centrals.onServiceReset()
        assertFalse(centrals.mayHaveSubscribed("B"))
    }

    @Test
    fun onceTheTableHasBeenFullNobodyIsNamedUntilTheServiceIsReset() {
        val centrals = centrals(max = 2)
        centrals.onSeen("A", now = 0)
        centrals.onSeen("B", now = 0)
        centrals.onSeen("C", now = 100)

        // A and B are recorded and due; what is known is no longer everything.
        assertEquals(emptyList(), centrals.named(now = grace))
        assertEquals(emptyList(), centrals.named(now = again * 3))

        centrals.onServiceReset()
        centrals.onSeen("A", now = again * 3)
        assertEquals(listOf("A"), centrals.named(now = again * 3 + grace))
    }

    @Test
    fun whatWasDueNoLongerStandsOnceTheTableHasBeenFullButADropAlreadyBegunIsFinished() {
        val centrals = centrals(max = 2)
        centrals.onSeen("A", now = 0)
        centrals.onSeen("B", now = 0)
        val (dueA, dueB) = centrals.due(now = grace)

        // No place for C: from here on what is known is not everything.
        centrals.onSeen("C", now = grace)

        assertFalse(centrals.isStillDue(dueA), "something new was begun with the table full")
        // A's record is as true as it was, and whoever claimed its link before must be able to
        // finish the drop.
        assertTrue(centrals.takeIfStillDue(dueA, now = grace + 300))
        assertFalse(centrals.isStillDue(dueB))
    }

    @Test
    fun whatIsRefusedForWantOfRoomIsReported() {
        val centrals = centrals(max = 2)
        assertEquals(UnsubscribedCentrals.Refusing(tracking = false, asking = false), centrals.refusing())

        // Two places to remember askings, both taken when a third central comes due.
        centrals.onSeen("A", now = 0)
        centrals.onSeen("B", now = 0)
        assertEquals(listOf("A", "B"), centrals.named(now = grace))
        centrals.onGone("A")
        centrals.onGone("B")
        centrals.onSeen("C", now = grace)
        assertEquals(emptyList(), centrals.named(now = grace * 2))
        assertEquals(UnsubscribedCentrals.Refusing(tracking = false, asking = true), centrals.refusing())

        // Room again once the first askings are a period old.
        assertEquals(listOf("C"), centrals.named(now = grace + again))
        assertEquals(UnsubscribedCentrals.Refusing(tracking = false, asking = false), centrals.refusing())

        // No place for a third connection.
        centrals.onSeen("D", now = grace + again)
        centrals.onSeen("E", now = grace + again)
        assertEquals(UnsubscribedCentrals.Refusing(tracking = true, asking = false), centrals.refusing())

        centrals.onServiceReset()
        assertEquals(UnsubscribedCentrals.Refusing(tracking = false, asking = false), centrals.refusing())
    }

    @Test
    fun noMoreConnectionsAreRecordedThanThereIsRoomFor() {
        val centrals = centrals(max = 4)

        // Far more addresses than there is room for, none of which is ever reported gone.
        repeat(100) { index -> centrals.onSeen("flood-$index", now = 1_000L + index) }
        centrals.named(now = 1_000_000)
        centrals.named(now = 2_000_000)

        assertEquals(4, centrals.remembered(), "the four connections there is room for, and no asking")
    }

    @Test
    fun aCentralThatCouldNotBeRecordedIsNotNamedLaterForNotHavingSubscribed() {
        val centrals = centrals(max = 2)
        centrals.onSeen("A", now = 0); centrals.onSubscribed("A")
        centrals.onSeen("B", now = 0); centrals.onSubscribed("B")

        // No room: neither C's connection nor its subscription is recorded.
        centrals.onSeen("C", now = 100)
        centrals.onSubscribed("C")

        // A place comes free, and C, which did subscribe, writes.
        centrals.onGone("A")
        centrals.onSeen("C", now = 200)

        assertEquals(emptyList(), centrals.named(now = 200 + grace))
        assertEquals(emptyList(), centrals.named(now = 200 + again * 3))
    }

    @Test
    fun aServiceResetLetsCentralsBeRecordedAgainAfterTheTableWasFull() {
        val centrals = centrals(max = 2)
        centrals.onSeen("A", now = 0); centrals.onSubscribed("A")
        centrals.onSeen("B", now = 0); centrals.onSubscribed("B")
        centrals.onSeen("C", now = 100)

        centrals.onServiceReset()
        centrals.onSeen("C", now = 1_000)

        assertEquals(listOf("C"), centrals.named(now = 1_000 + grace))
    }

    @Test
    fun noMoreCentralsAreAskedInOnePeriodThanCanBeRemembered() {
        val centrals = centrals(max = 4)
        listOf("A", "B", "C", "D").forEach { centrals.onSeen(it, now = 0) }
        assertEquals(listOf("A", "B", "C", "D"), centrals.named(now = grace))
        listOf("A", "B", "C", "D").forEach { centrals.onGone(it) }

        // Four more that never subscribe: there is no room to remember having asked them.
        listOf("E", "F", "G", "H").forEach { centrals.onSeen(it, now = grace + 1_000) }
        assertEquals(emptyList(), centrals.named(now = grace + 1_000 + grace))
        assertEquals(emptyList(), centrals.named(now = grace + again - 1))

        // The first four were asked a whole period ago.
        assertEquals(listOf("E", "F", "G", "H"), centrals.named(now = grace + again))
    }

    @Test
    fun whoWasAskedIsRememberedForTheWholePeriodHoweverManyOthersCome() {
        val centrals = centrals(max = 4)
        centrals.onSeen("A", now = 0)
        assertEquals(listOf("A"), centrals.named(now = grace))
        centrals.onGone("A")

        // Many others come, stay unsubscribed past their grace and go.
        var named = 0
        repeat(100) { index ->
            val seenAt = grace + 1_000L + index * 1_000L
            centrals.onSeen("other-$index", now = seenAt)
            named += centrals.due(now = seenAt + grace).size
            centrals.onGone("other-$index")
        }
        assertEquals(3, named, "only as many others are asked as there is room to remember beside A")

        // A is back and still does not subscribe.
        centrals.onSeen("A", now = grace + 200_000)
        assertEquals(emptyList(), centrals.named(now = grace + again - 1))
        assertEquals(listOf("A"), centrals.named(now = grace + again))
    }

    @Test
    fun whoWasAskedIsForgottenOnceThePeriodHasPassed() {
        val centrals = centrals(max = 4)
        // Addresses that were asked to leave and never came back, as rotating addresses do, three
        // to a period: each finds room only because those from more than a period ago are gone.
        repeat(100) { index ->
            centrals.onSeen("addr-$index", now = index * 100_000L)
            assertEquals(listOf("addr-$index"), centrals.named(now = index * 100_000L + grace))
            centrals.onGone("addr-$index")
        }
        assertEquals(3, centrals.remembered(), "the askings of the last period, and no others")
    }

    @Test
    fun centralsComingAndGoingDoNotDisturbOneThatStays() {
        val centrals = centrals(max = 2)
        centrals.onSeen("stays", now = 0)
        repeat(10) { index ->
            centrals.onSeen("other-$index", now = 1_000)
            centrals.onGone("other-$index")
        }

        assertEquals(listOf("stays"), centrals.named(now = grace))
    }
}
