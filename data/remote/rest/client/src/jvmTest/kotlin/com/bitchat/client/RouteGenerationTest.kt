package com.bitchat.client

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.yield
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RouteGenerationTest {
    @Test
    fun `revocation invalidates prior lease and cancels its active call`() = runBlocking {
        val routes = RouteGenerations()
        val lease = routes.admit()
        val admitted = CompletableDeferred<Unit>()
        val active = launch {
            routes.track(lease) {
                admitted.complete(Unit)
                kotlinx.coroutines.awaitCancellation()
            }
        }
        admitted.await()

        routes.revokeAndJoin()

        assertFalse(routes.isCurrent(lease))
        assertTrue(active.isCancelled)
    }

    @Test
    fun `admission stays closed until a policy transition is published`() = runBlocking {
        val routes = RouteGenerations()
        val oldLease = routes.admit()
        val tracked = CompletableDeferred<Unit>()
        val active = launch {
            routes.track(oldLease) {
                tracked.complete(Unit)
                kotlinx.coroutines.awaitCancellation()
            }
        }
        tracked.await()

        val policyPublication = CompletableDeferred<Unit>()
        val transition = launch {
            routes.transition {
                policyPublication.await()
            }
        }
        yield()

        val directAdmission = async { routes.admit() }
        yield()
        assertFalse(directAdmission.isCompleted, "a direct route escaped before ON was published")

        policyPublication.complete(Unit)
        withTimeout(1_000) { transition.join() }
        val newLease = withTimeout(1_000) { directAdmission.await() }

        assertFalse(routes.isCurrent(oldLease))
        assertTrue(routes.isCurrent(newLease))
        assertTrue(active.isCancelled)
    }

    @Test
    fun `cancelling a transition while old work drains still settles admission`() = runBlocking {
        val routes = RouteGenerations()
        val lease = routes.admit()
        val draining = CompletableDeferred<Unit>()
        val releaseDrain = CompletableDeferred<Unit>()
        val active = launch {
            routes.track(lease) {
                try {
                    kotlinx.coroutines.awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        draining.complete(Unit)
                        releaseDrain.await()
                    }
                }
            }
        }

        val transition = launch { routes.transition {} }
        draining.await()
        transition.cancel()
        releaseDrain.complete(Unit)
        transition.join()

        withTimeout(1_000) { routes.admit() }
        active.cancelAndJoin()
    }

    @Test
    fun `a failed transport retirement prevents publication and is retried`() = runBlocking {
        val routes = RouteGenerations()
        var attempts = 0
        var published = false
        routes.retainFailedRetirement {
            attempts++
            check(attempts == 2) { "synthetic Curl teardown timeout" }
        }

        assertFailsWith<RouteRetirementException> {
            routes.transition { published = true }
        }
        assertFalse(published, "ON was published after teardown failed")

        routes.transition { published = true }

        assertTrue(published)
        assertTrue(attempts == 2, "the unfinished teardown was not retained for retry")
    }

    @Test
    fun `a failed first retirement keeps every unattempted direct retirement pending`() = runBlocking {
        val routes = RouteGenerations()
        val attempts = mutableListOf<String>()
        var firstMayFinish = false
        var secondMayFinish = false
        var thirdMayFinish = false
        var published = false

        routes.retainFailedRetirement {
            attempts += "first"
            check(firstMayFinish) { "first direct transport is still alive" }
        }
        routes.retainFailedRetirement {
            attempts += "second"
            check(secondMayFinish) { "second direct transport is still alive" }
        }
        routes.retainFailedRetirement {
            attempts += "third"
            check(thirdMayFinish) { "third direct transport is still alive" }
        }

        assertFailsWith<RouteRetirementException> { routes.transition { published = true } }
        assertTrue(attempts == listOf("first"), "unattempted retirements must remain pending")
        assertFalse(published)

        firstMayFinish = true
        assertFailsWith<RouteRetirementException> { routes.transition { published = true } }
        assertTrue(attempts == listOf("first", "first", "second"))
        assertFalse(published, "ON published before every direct transport retired")

        secondMayFinish = true
        assertFailsWith<RouteRetirementException> { routes.transition { published = true } }
        assertTrue(attempts == listOf("first", "first", "second", "second", "third"))
        assertFalse(published, "ON published before the final direct transport retired")

        thirdMayFinish = true
        routes.transition { published = true }

        assertTrue(published)
        assertTrue(
            attempts == listOf("first", "first", "second", "second", "third", "third"),
            "every retained direct transport must confirm retirement before ON publishes",
        )
    }

    @Test
    fun `OFF publishes despite an unconfirmed direct retirement but a later ON stays closed`() = runBlocking {
        val routes = RouteGenerations()
        var offPublished = false
        var onPublished = false
        routes.retainFailedRetirement { error("direct Curl teardown completed exceptionally") }

        routes.transition(waitForDirectRetirements = false) { offPublished = true }

        assertTrue(offPublished, "OFF must restore direct transport even when retirement failed")
        assertFailsWith<RouteRetirementException> { routes.transition { onPublished = true } }
        assertFalse(onPublished, "ON must remain fail-closed until direct retirement confirms")
    }

    @Test
    fun `a failed Tor retirement never blocks another route policy`() = runBlocking {
        val routes = RouteGenerations()
        var published = false
        var retirementAttempts = 0
        routes.retainFailedRetirement(blocksTorEnable = false) {
            retirementAttempts++
            error("Tor Curl teardown completed exceptionally")
        }

        routes.transition { published = true }

        assertTrue(published, "a retired Tor route cannot prevent policy recovery")
        assertTrue(retirementAttempts == 0, "a failed Tor retirement was retained instead of discarded")
    }
}
