package com.bitchat.repo.repositories

import com.bitchat.bluetooth.service.BluetoothMeshService
import com.bitchat.bluetooth.model.PeerInfo
import com.bitchat.domain.chat.model.BitchatMessage
import com.bitchat.domain.user.eventbus.UserEventBus
import com.bitchat.domain.user.model.FavoriteRelationship
import com.bitchat.domain.user.model.UserEvent
import com.bitchat.domain.user.repository.UserRepository
import com.bitchat.local.prefs.UserPreferences
import com.bitchat.nostr.Bech32
import com.bitchat.nostr.NostrEmbeddedBitChat
import com.bitchat.nostr.NostrPreferences
import com.bitchat.nostr.NostrSubscriptionId
import com.bitchat.nostr.model.NostrEvent
import com.bitchat.nostr.model.NostrIdentity
import com.bitchat.nostr.model.NostrKind
import com.bitchat.nostr.participant.NostrParticipantTracker
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** What a Nostr key the user has nothing to do with can make the app keep: names and favourites. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatRepoLearnedNamesTest {

    @Test
    fun keysPostingInAGeohashWriteNothingToPreferences() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val subscriptions = mutableListOf<Subscription>()
        val preferences = Recorded(UserPreferences::class.java, emptyUserPreferences())
        val users = Recorded(
            UserRepository::class.java,
            mockk<UserRepository>(relaxed = true).also { coEvery { it.getUserState() } returns null },
        )
        try {
            val repo = chatRepo(scope, dispatcher, subscriptions, userPreferences = preferences.proxy, userRepository = users.proxy)
            repo.getGeohashMessages(GEOHASH)
            val relay = subscriptions.single { it.id == NostrSubscriptionId.geohash(GEOHASH) }.handler
            // Starting is what removes the names an older version saved; from here on, only traffic.
            assertTrue("clearPeerDisplayNames" in preferences.calls)
            preferences.calls.clear()
            users.calls.clear()

            repeat(200) { index -> relay(geohashEvent("event-$index", nostrKey(index), nickname = "name-$index")) }

            assertEquals(200, repo.getGeohashMessages(GEOHASH).size, "every one of them was accepted and is shown")
            assertEquals("name-199", repo.getDisplayName(chatOf(nostrKey(199))), "and its name learned")
            assertEquals(emptyList(), preferences.writes())
            assertEquals(emptyList(), users.writes())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aGeohashEventThatFailsProofOfWorkLeavesNoName() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val subscriptions = mutableListOf<Subscription>()
        val preferences = emptyUserPreferences()
        val nostrPreferences = mockk<NostrPreferences>(relaxed = true).also {
            every { it.getPowEnabled() } returns true
            every { it.getPowDifficulty() } returns 32
        }
        try {
            val repo = chatRepo(scope, dispatcher, subscriptions, userPreferences = preferences, nostrPreferences = nostrPreferences)
            repo.getGeohashMessages(GEOHASH)
            val relay = subscriptions.single { it.id == NostrSubscriptionId.geohash(GEOHASH) }.handler
            val sender = nostrKey(1)

            relay(geohashEvent("no-work-done", sender, nickname = "mallory"))

            assertEquals(emptyList(), repo.getGeohashMessages(GEOHASH), "the event itself is refused")
            assertEquals(null, repo.getDisplayName(chatOf(sender)))
            verify(exactly = 1) { preferences.clearPeerDisplayNames() }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aFavouriteNotificationFromAKeyWithNoRelationshipSavesNothing() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val subscriptions = mutableListOf<Subscription>()
        val statefulPreferences = StatefulFavoritePreferences()
        val preferences = statefulPreferences.preferences
        try {
            chatRepo(scope, dispatcher, subscriptions, userPreferences = preferences, nostrIdentity = ME)
            val relay = subscriptions.single { it.id == NostrSubscriptionId.directMessages(ME.publicKeyHex) }.handler

            repeat(50) { index -> relay(giftWrap("wrap-$index", nostrKey(index), "[FAVORITED]:npub1whatever$index")) }

            assertEquals(0, statefulPreferences.favoriteWrites)
            assertEquals(0, statefulPreferences.mappingWrites)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aGeohashNicknameIsCleanedAndCutBeforeItIsShownOrUsedToLabelAPrivateChat() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val subscriptions = mutableListOf<Subscription>()
        try {
            val repo = chatRepo(scope, dispatcher, subscriptions, nostrIdentity = ME)
            repo.getGeohashMessages(GEOHASH)
            val channel = subscriptions.single { it.id == NostrSubscriptionId.geohash(GEOHASH) }.handler
            val direct = subscriptions.single { it.id == NostrSubscriptionId.directMessages(ME.publicKeyHex) }.handler
            val sender = nostrKey(1)

            // An escape sequence that clears a terminal, a right-to-left override, and 200 more characters.
            channel(geohashEvent("event-1", sender, nickname = "al\u001b[2Jice\u202e" + "x".repeat(200)))

            val shown = "al[2Jice" + "x".repeat(42)
            assertEquals(50, shown.length)
            assertEquals(shown, repo.getGeohashMessages(GEOHASH).single().sender)
            assertEquals(shown, repo.getDisplayName(chatOf(sender)))

            direct(giftWrap("wrap-1", sender, "hello"))
            assertEquals(shown, repo.getPrivateChats().getValue(chatOf(sender)).single().sender, "the name learned in the channel labels the chat")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun onlyTheNewestLearnedNamesAreKeptAndOneTheUserRecordedOutlastsThem() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val subscriptions = mutableListOf<Subscription>()
        try {
            val repo = chatRepo(scope, dispatcher, subscriptions)
            repo.getGeohashMessages(GEOHASH)
            val relay = subscriptions.single { it.id == NostrSubscriptionId.geohash(GEOHASH) }.handler
            // The user opened a conversation with this person before the flood.
            val chosen = nostrKey(5_000)
            repo.storePersonDataForDM(chatOf(chosen), chosen, sourceGeohash = null, displayName = "chosen")

            repeat(1_400) { index -> relay(geohashEvent("event-$index", nostrKey(index), nickname = "name-$index")) }

            assertEquals(null, repo.getDisplayName(chatOf(nostrKey(0))))
            assertEquals(null, repo.getDisplayName(chatOf(nostrKey(62))), "1400 names against room for 1337: the first 63 are forgotten")
            assertEquals("name-63", repo.getDisplayName(chatOf(nostrKey(63))))
            assertEquals("name-1399", repo.getDisplayName(chatOf(nostrKey(1_399))))
            assertEquals("chosen", repo.getDisplayName(chatOf(chosen)))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun anEventRefusedForProofOfWorkLeavesNoPresence() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val subscriptions = mutableListOf<Subscription>()
        val tracker = NostrParticipantTracker()
        val nostrPreferences = mockk<NostrPreferences>(relaxed = true).also {
            every { it.getPowEnabled() } returns true
            every { it.getPowDifficulty() } returns 32
        }
        try {
            val repo = chatRepo(scope, dispatcher, subscriptions, nostrPreferences = nostrPreferences, participantTracker = tracker)
            repo.getGeohashMessages(GEOHASH)
            tracker.setCurrentGeohash(GEOHASH)
            val relay = subscriptions.single { it.id == NostrSubscriptionId.geohash(GEOHASH) }.handler
            val sender = nostrKey(1)

            // An id with no leading zero bits at all.
            relay(geohashEvent("ff".repeat(32), sender, nickname = "mallory"))

            assertEquals(emptyList(), tracker.currentGeohashPeople.value)
            assertEquals(null, tracker.getNicknameByPubkey(sender))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun postingMarksTheUserPresentAndACopyOfAnEventAlreadyShownMarksNobody() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val subscriptions = mutableListOf<Subscription>()
        val clock = MovableClock(Instant.fromEpochSeconds(Clock.System.now().epochSeconds))
        val tracker = NostrParticipantTracker(clock)
        try {
            val repo = chatRepo(
                scope, dispatcher, subscriptions,
                geohashIdentity = ME,
                participantTracker = tracker,
                configureNostrClient = { client ->
                    coEvery { client.createEphemeralGeohashEvent(any(), any(), any(), any(), any(), any()) } answers {
                        geohashEvent("own-1", ME.publicKeyHex, nickname = "me", createdAt = clock.now().epochSeconds.toInt())
                    }
                },
            )
            repo.getGeohashMessages(GEOHASH)
            tracker.setCurrentGeohash(GEOHASH)
            val relay = subscriptions.single { it.id == NostrSubscriptionId.geohash(GEOHASH) }.handler
            val other = nostrKey(1)
            val start = clock.now().epochSeconds.toInt()
            relay(geohashEvent("event-1", other, nickname = "alice", createdAt = start))

            clock.advance(4.minutes)
            repo.sendGeohashMessage("hi", GEOHASH, "me")
            // The relay echoes the user's message, and hands over a copy of the other event, both
            // dated now and under another name. Neither is new: neither is looked at.
            relay(geohashEvent("own-1", ME.publicKeyHex, nickname = "renamed", createdAt = start + 240))
            relay(geohashEvent("event-1", other, nickname = "renamed", createdAt = start + 240))

            // Six minutes after the other one's only event, two after the user posted.
            clock.advance(2.minutes)
            tracker.setCurrentGeohash(GEOHASH)
            val people = tracker.currentGeohashPeople.value
            assertEquals(listOf(ME.publicKeyHex), people.map { it.id })
            assertEquals("me", people.single().displayName)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aNotificationOverNostrFromSomeoneTheUserFavouritedMakesItMutualWithTheSendersOwnKey() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val subscriptions = mutableListOf<Subscription>()
        val sender = nostrKey(3)
        val record = sender.take(16)
        val saved = StatefulFavoritePreferences(mapOf(record to favorite(record, isFavorite = true, nickname = "my friend")))
        try {
            val repo = chatRepo(scope, dispatcher, subscriptions, userPreferences = saved.preferences, nostrIdentity = ME)
            val relay = subscriptions.single { it.id == NostrSubscriptionId.directMessages(ME.publicKeyHex) }.handler

            relay(giftWrap("wrap-1", sender, "[FAVORITED]:npub1typedbythesender"))

            val now = saved.favorites.getValue(record)
            assertTrue(now.isMutual)
            assertEquals(npubOf(sender), now.peerNostrPublicKey, "the key the message came from, not the one typed in it")
            assertEquals("my friend", now.peerNickname)
            assertEquals(emptyMap(), repo.getPrivateChats(), "a notification is not a chat message")

            // Said again, nothing is written.
            val recordWrites = saved.favoriteWrites
            relay(giftWrap("wrap-2", sender, "[FAVORITED]:npub1typedbythesender"))
            assertEquals(recordWrites, saved.favoriteWrites)
            assertEquals(0, saved.mappingWrites)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun meshPeersTheUserNeverFavouritedAreRecordedUpToTheLimitAndNeverDisplaceAFavourite() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val mine = "f00d000000000001"
        val saved = StatefulFavoritePreferences(mapOf(mine to favorite(mine, isFavorite = true)))
        try {
            // A record is named after what the peer announces; the sender field of a message names nothing.
            val mesh = mockk<BluetoothMeshService>(relaxed = true)
            every { mesh.getPeerInfo(any()) } answers {
                val id = firstArg<String>()
                PeerInfo(id, "announced-${id.toInt(16)}", true, true, null, null, false, Clock.System.now())
            }
            val repo = chatRepo(scope, dispatcher, mutableListOf(), mesh = mesh, userPreferences = saved.preferences, clock = SteppingClock())

            repeat(205) { index ->
                // Each says which Nostr key is its own; the key is well formed, so it is kept.
                repo.didReceiveAuthenticatedPrivateMessage(meshNotification(index, "[FAVORITED]:${npubOf(nostrKey(index))}"))
            }

            val theirs = saved.favorites.values.filter { !it.isFavorite }
            assertEquals(200, theirs.size)
            assertTrue(theirs.all { it.theyFavoritedUs })
            assertEquals("announced-204", saved.favorites.getValue(meshPeer(204)).peerNickname)
            assertEquals(npubOf(nostrKey(204)), saved.favorites.getValue(meshPeer(204)).peerNostrPublicKey)
            assertFalse(meshPeer(4) in saved.favorites, "the five recorded first made room")
            assertTrue(meshPeer(5) in saved.favorites)
            assertTrue(saved.favorites.getValue(mine).isFavorite, "the user's own favourite is not among those that go")
            assertEquals(0, saved.mappingWrites)
            assertEquals(emptyMap(), repo.getPrivateChats())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun theKeyMappingsOlderVersionsSavedAreRemovedAtStartAndNoneIsEverWrittenAgain() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val subscriptions = mutableListOf<Subscription>()
        val friend = nostrKey(3)
        val mine = friend.take(16)
        val theirs = (0 until 3).associate { index -> meshPeer(index) to favorite(meshPeer(index), isFavorite = false) }
        val saved = StatefulFavoritePreferences(
            initial = theirs + (mine to favorite(mine, isFavorite = true)),
            // One beside each record, as older versions wrote them, and one whose record is long gone.
            initialMappings = theirs.keys.associateWith { "npub-of-$it" } + (mine to "npub-of-mine") + ("0rphan0000000000" to "npub-of-nobody"),
        )
        try {
            val repo = chatRepo(scope, dispatcher, subscriptions, userPreferences = saved.preferences, nostrIdentity = ME, clock = SteppingClock())
            val relay = subscriptions.single { it.id == NostrSubscriptionId.directMessages(ME.publicKeyHex) }.handler

            assertEquals(emptyMap(), saved.mappings, "a second copy of the key each record carries itself")
            assertEquals(1, saved.mappingWrites)
            assertEquals(4, saved.favorites.size, "the records stay")

            // Whatever arrives afterwards: a stranger over the mesh with a key, a favourite over Nostr.
            repo.didReceiveAuthenticatedPrivateMessage(meshNotification(7, "[FAVORITED]:${npubOf(nostrKey(7))}"))
            relay(giftWrap("wrap-1", friend, "[FAVORITED]:npub1typed"))

            assertEquals(npubOf(nostrKey(7)), saved.favorites.getValue(meshPeer(7)).peerNostrPublicKey, "the key is in the record")
            assertEquals(npubOf(friend), saved.favorites.getValue(mine).peerNostrPublicKey)
            assertEquals(emptyMap(), saved.mappings)
            verify(exactly = 0) { saved.preferences.setNostrPubkeyForPeerID(any(), any()) }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aNotificationSaidAgainWritesNothingAndTheChangeIsAnnouncedOnce() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val saved = StatefulFavoritePreferences()
        val events = mockk<UserEventBus>(relaxed = true).also { every { it.events() } returns emptyFlow() }
        try {
            val repo = chatRepo(scope, dispatcher, mutableListOf(), userPreferences = saved.preferences, userEventBus = events, clock = SteppingClock())

            repeat(20) { repo.didReceiveAuthenticatedPrivateMessage(meshNotification(1, "[FAVORITED]:")) }

            assertEquals(1, saved.favoriteWrites, "the first one recorded it; the other nineteen said nothing new")
            coVerify(exactly = 1) { events.update(UserEvent.FavoriteStatusChanged(meshPeer(1))) }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun recordsOlderVersionsSavedAreCutToTheLimitAtStartInOneWrite() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val mine = "f00d000000000001"
        val old = (0 until 250).associate { index -> meshPeer(index) to favorite(meshPeer(index), isFavorite = false, lastUpdated = index.toLong()) }
        val saved = StatefulFavoritePreferences(old + (mine to favorite(mine, isFavorite = true, lastUpdated = 0)))
        val events = mockk<UserEventBus>(relaxed = true).also { every { it.events() } returns emptyFlow() }
        try {
            chatRepo(scope, dispatcher, mutableListOf(), userPreferences = saved.preferences, userEventBus = events)

            assertEquals(201, saved.favorites.size)
            assertEquals(1, saved.favoriteWrites)
            assertFalse(meshPeer(49) in saved.favorites, "the fifty recorded longest ago are gone")
            assertTrue(meshPeer(50) in saved.favorites)
            assertTrue(mine in saved.favorites)
            // Whoever read the favourites before the trim is told to read them again.
            coVerify(exactly = 1) { events.update(any<UserEvent.FavoriteStatusChanged>()) }
        } finally {
            scope.cancel()
        }
    }

    private fun nostrKey(index: Int) = index.toString(16).padStart(4, '0').repeat(16)

    private fun meshPeer(index: Int) = index.toString(16).padStart(16, '0')

    /** A favourite notification as it comes out of a Noise session with the mesh peer [index]. */
    private fun meshNotification(index: Int, text: String) = BitchatMessage(
        id = "notification-$index-${text.hashCode()}",
        sender = "peer-$index",
        senderPeerID = meshPeer(index),
        content = text,
        timestamp = Clock.System.now(),
        isPrivate = true,
    )

    private fun npubOf(pubkeyHex: String) = Bech32.encode("npub", pubkeyHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray())

    private fun favorite(key: String, isFavorite: Boolean, nickname: String = key, lastUpdated: Long = 1) = FavoriteRelationship(
        peerNoisePublicKeyHex = key,
        peerNostrPublicKey = null,
        peerNickname = nickname,
        isFavorite = isFavorite,
        theyFavoritedUs = !isFavorite,
        favoritedAt = 1,
        lastUpdated = lastUpdated,
    )

    private class MovableClock(private var current: Instant) : Clock {
        override fun now(): Instant = current
        fun advance(by: Duration) { current += by }
    }

    /** A clock that never gives the same instant twice, so "recorded longest ago" has no ties. */
    private class SteppingClock : Clock {
        private var current = Instant.fromEpochSeconds(1_000_000)
        override fun now(): Instant = current.also { current += 1.milliseconds }
    }

    private fun chatOf(pubkeyHex: String) = "nostr_${pubkeyHex.take(16)}"

    private fun geohashEvent(
        id: String,
        pubkey: String,
        nickname: String,
        createdAt: Int = Clock.System.now().epochSeconds.toInt(),
    ) = NostrEvent(
        id = id,
        pubkey = pubkey,
        createdAt = createdAt,
        kind = NostrKind.EPHEMERAL_EVENT,
        tags = listOf(listOf("g", GEOHASH), listOf("n", nickname)),
        content = "hi",
    )

    /** A gift wrap as the harness's client "decrypts" it: the embedded private message, as it is. */
    private fun giftWrap(id: String, senderPubkey: String, text: String) = NostrEvent(
        id = id,
        pubkey = senderPubkey,
        createdAt = Clock.System.now().epochSeconds.toInt(),
        kind = NostrKind.GIFT_WRAP,
        tags = emptyList(),
        content = NostrEmbeddedBitChat.encodePMForNostr(
            content = text,
            messageID = "message-of-$id",
            recipientPeerID = "0102030405060708",
            senderPeerID = "1112131415161718",
        )!!,
    )

    private companion object {
        const val GEOHASH = "9q8yy"
        val ME = NostrIdentity(privateKeyHex = "a1".repeat(32), publicKeyHex = "b2".repeat(32), npub = "npub1me", createdAt = 0)
    }
}
