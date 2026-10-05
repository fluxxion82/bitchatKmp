package com.bitchat.repo.repositories

import com.bitchat.nostr.NostrSubscriptionId
import com.bitchat.nostr.model.NostrKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class ChatRepoGeohashSubscriptionTest {

    @Test
    fun getGeohashMessagesCreatesOneChatSubscription() = runTest {
        val subscriptions = mutableListOf<Subscription>()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)

        try {
            val chatRepo = chatRepo(scope, dispatcher, subscriptions)
            val geohash = "9q8yy"

            chatRepo.getGeohashMessages(geohash)

            val geohashSubscriptions = subscriptions.filter { subscription ->
                subscription.filter.kinds == listOf(NostrKind.EPHEMERAL_EVENT) &&
                    subscription.filter.tagFilters?.get("g") == listOf(geohash)
            }

            assertEquals(1, geohashSubscriptions.size)
            assertEquals(NostrSubscriptionId.geohash(geohash), geohashSubscriptions.single().id)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun getGeohashMessagesDoesNotSubscribeTwiceForTheSameGeohash() = runTest {
        val subscriptions = mutableListOf<Subscription>()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)

        try {
            val chatRepo = chatRepo(scope, dispatcher, subscriptions)
            val geohash = "9q8yy"

            chatRepo.getGeohashMessages(geohash)
            chatRepo.getGeohashMessages(geohash)

            val geohashSubscriptions = subscriptions.filter { subscription ->
                subscription.id == NostrSubscriptionId.geohash(geohash)
            }

            assertEquals(1, geohashSubscriptions.size)
        } finally {
            scope.cancel()
        }
    }

}
