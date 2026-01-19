package com.bitchat.repo.repositories

import com.bitchat.domain.base.CoroutineScopeFacade
import com.bitchat.domain.base.CoroutinesContextFacade
import com.bitchat.domain.location.eventbus.LocationEventBus
import com.bitchat.domain.location.model.GeoPerson
import com.bitchat.domain.location.model.GeoPoint
import com.bitchat.domain.location.model.GeohashChannel
import com.bitchat.domain.location.model.GeohashChannelLevel
import com.bitchat.domain.location.model.Note
import com.bitchat.domain.location.model.PermissionState
import com.bitchat.domain.location.repository.LocationRepository
import com.bitchat.local.prefs.BookmarkPreferences
import com.bitchat.local.prefs.GeohashPreferences
import com.bitchat.local.service.GeocoderService
import com.bitchat.local.service.LocationService
import com.bitchat.nostr.NostrClient
import com.bitchat.nostr.NostrPreferences
import com.bitchat.nostr.NostrRelay
import com.bitchat.nostr.logging.logNostrDebug
import com.bitchat.nostr.model.NostrFilter
import com.bitchat.nostr.participant.NostrParticipantTracker
import com.bitchat.nostr.util.GeohashUtils
import com.bitchat.repo.location.resolveBookmarkName
import com.bitchat.repo.mappers.toDomain
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class LocationRepo(
    private val nostrClient: NostrClient,
    private val nostrRelay: NostrRelay,
    private val nostrPreferences: NostrPreferences,
    private val geohashPreferences: GeohashPreferences,
    private val locationService: LocationService,
    private val bookmarkPrefs: BookmarkPreferences,
    private val participantTracker: NostrParticipantTracker,
    private val geocoder: GeocoderService,
    private val coroutinesContextFacade: CoroutinesContextFacade,
    private val coroutineScopeFacade: CoroutineScopeFacade,
    private val locationEventBus: LocationEventBus,
) : LocationRepository {
    private var isTeleported: Boolean = false
    private val locationNames = mutableMapOf<GeohashChannelLevel, String>()
    private val locationNamesLock = SynchronizedObject()
    private var lastFix: GeoPoint? = null
    private var lastResolvedGeohash: String? = null
    private val samplingLock = SynchronizedObject()
    private val samplingSubscriptions = mutableMapOf<String, String>()
    private val sampledGeohashes = mutableSetOf<String>()

    override suspend fun getNotes(geoHash: String): List<Note> = withContext(coroutinesContextFacade.io) {
        logNostrDebug("LocationRepo", "getNotes: querying notes for geohash=$geoHash")

        val geohashes = GeohashUtils.neighbors(geoHash) + geoHash
        val collectedEvents = mutableListOf<com.bitchat.nostr.model.NostrEvent>()
        val eventsLock = SynchronizedObject()

        nostrRelay.ensureGeohashRelaysConnected(geoHash, nRelays = 5, includeDefaults = true)

        val subscriptionIds = mutableListOf<String>()

        try {
            geohashes.forEachIndexed { index, hash ->
                val subId = "notes_${geoHash}_$index"
                val filter = NostrFilter.geohashNotes(hash, limit = 100)

                nostrRelay.subscribe(
                    subscriptionId = subId,
                    filter = filter,
                    handler = { event ->
                        synchronized(eventsLock) {
                            collectedEvents.add(event)
                        }
                    },
                    originGeohash = hash
                )
                subscriptionIds.add(subId)
            }

            delay(2.seconds)

        } finally {
            subscriptionIds.forEach { subId ->
                try {
                    nostrRelay.unsubscribe(subId)
                } catch (e: Exception) {
                    // Ignore unsubscribe errors
                }
            }
        }

        val notes = synchronized(eventsLock) {
            collectedEvents
                .distinctBy { it.id }
                .map { event ->
                    val nicknameTag = event.tags.find { it.firstOrNull() == "n" }?.getOrNull(1)
                    Note(
                        id = event.id,
                        pubkey = event.pubkey,
                        content = event.content,
                        createdAt = event.createdAt,
                        nickname = nicknameTag
                    )
                }
                .sortedByDescending { it.createdAt }
                .take(500)
        }

        logNostrDebug("LocationRepo", "getNotes: found ${notes.size} notes for geohash=$geoHash")
        notes
    }

    override suspend fun sendNote(
        content: String,
        nickname: String,
        geohash: String
    ) = withContext(coroutinesContextFacade.io) {
        logNostrDebug("LocationRepo", "sendNote: sending note to geohash=$geohash")

        val identity = nostrClient.deriveIdentity(geohash)
        val event = nostrClient.createGeohashTextNote(
            content = content,
            geohash = geohash,
            senderIdentity = identity,
            nickname = nickname.ifEmpty { null }
        )

        nostrRelay.sendEventToGeohash(event, geohash, includeDefaults = true, nRelays = 5)

        logNostrDebug("LocationRepo", "sendNote: note sent successfully")
    }

    override suspend fun getLocationGeohash(level: GeohashChannelLevel): String =
        withContext(coroutinesContextFacade.io) {
            val fix = locationService.getCurrentLocation()
            GeohashUtils.encode(fix.lat, fix.lon, level.precision)
        }

    override suspend fun getAvailableGeohashChannels(): List<GeohashChannel> = withContext(coroutinesContextFacade.io) {
        try {
            val fix = locationService.getCurrentLocation()
            lastFix = fix
            resolveLocationNamesForFix(fix)

            val levels = listOf(
                GeohashChannelLevel.BLOCK,
                GeohashChannelLevel.NEIGHBORHOOD,
                GeohashChannelLevel.CITY,
                GeohashChannelLevel.PROVINCE,
                GeohashChannelLevel.REGION
            )

            levels.map { level ->
                val geohash = GeohashUtils.encode(fix.lat, fix.lon, level.precision)
                GeohashChannel(
                    level = level,
                    geohash = geohash
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    override suspend fun getBookmarkedChannel(): List<GeohashChannel> = withContext(coroutinesContextFacade.io) {
        TODO("Not yet implemented")
    }

    override suspend fun sendGeohashMessage(
        content: String,
        channel: GeohashChannel,
        myPeerId: String
    ) = withContext(coroutinesContextFacade.io) {
        val identity = nostrClient.deriveIdentity(channel.geohash)
        val teleported = false
        val event = nostrClient.createEphemeralGeohashEvent(content, channel.geohash, identity, myPeerId, teleported)

        nostrRelay.sendEventToGeohash(event, channel.geohash, includeDefaults = false, nRelays = 5)
    }

    override suspend fun getParticipantCounts(): Map<String, Int> = withContext(coroutinesContextFacade.io) {
        participantTracker.participantCounts.value
    }

    override suspend fun getCurrentGeohashPeople(): List<GeoPerson> = withContext(coroutinesContextFacade.io) {
        val people = participantTracker.currentGeohashPeople.value.toDomain()
        logNostrDebug(
            "LocationRepo",
            "getCurrentGeohashPeople -> ${people.size} people: ${people.joinToString { it.displayName }}"
        )
        people
    }

    override suspend fun beginGeohashSampling(geohashes: List<String>) = withContext(coroutinesContextFacade.io) {
        val normalized = geohashes
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .distinct()

        if (normalized.isEmpty()) {
            endGeohashSampling()
            return@withContext
        }

        val (toAdd, toRemove) = synchronized(samplingLock) {
            val incoming = normalized.toSet()
            val toAdd = incoming - sampledGeohashes
            val toRemove = sampledGeohashes - incoming
            sampledGeohashes.clear()
            sampledGeohashes.addAll(incoming)
            toAdd to toRemove
        }

        toRemove.forEach { geohash ->
            val subId = synchronized(samplingLock) { samplingSubscriptions.remove(geohash) }
            if (subId != null) {
                nostrRelay.unsubscribe(subId)
            }
        }

        val sinceMs = Clock.System.now().toEpochMilliseconds() - 86_400_000L
        toAdd.forEach { geohash ->
            nostrRelay.ensureGeohashRelaysConnected(geohash, nRelays = 5, includeDefaults = false)
            val relayUrls = nostrRelay.getRelaysForGeohash(geohash).toSet()
            val subId = "sampling_$geohash"
            val filter = NostrFilter.geohashEphemeral(geohash, since = sinceMs, limit = 200)

            nostrRelay.subscribe(
                subscriptionId = subId,
                filter = filter,
                handler = { event -> handleGeohashSamplingEvent(geohash, event) },
                targetRelayUrls = relayUrls.ifEmpty { null },
                originGeohash = geohash
            )

            synchronized(samplingLock) {
                samplingSubscriptions[geohash] = subId
            }
        }
    }

    override suspend fun endGeohashSampling() = withContext(coroutinesContextFacade.io) {
        val subs = synchronized(samplingLock) {
            val values = samplingSubscriptions.values.toList()
            samplingSubscriptions.clear()
            sampledGeohashes.clear()
            values
        }
        subs.forEach { nostrRelay.unsubscribe(it) }
    }

    override suspend fun getBookmarkedGeohashes(): List<String> = withContext(coroutinesContextFacade.io) {
        bookmarkPrefs.getBookmarks()
    }

    override suspend fun getBookmarkNames(): Map<String, String> = withContext(coroutinesContextFacade.io) {
        val bookmarks = bookmarkPrefs.getBookmarks()
        val names = bookmarkPrefs.getBookmarkNames().toMutableMap()

        for (geohash in bookmarks) {
            if (!names.containsKey(geohash)) {
                resolveBookmarkName(geohash, geocoder)?.let { name ->
                    names[geohash] = name
                    bookmarkPrefs.setBookmarkName(geohash, name)
                }
            }
        }

        names
    }

    override suspend fun toggleBookmark(geohash: String): Unit = withContext(coroutinesContextFacade.io) {
        if (bookmarkPrefs.isBookmarked(geohash)) {
            bookmarkPrefs.removeBookmark(geohash)
        } else {
            bookmarkPrefs.addBookmark(geohash)
            resolveBookmarkName(geohash, geocoder)?.let { name ->
                bookmarkPrefs.setBookmarkName(geohash, name)
            }
        }
    }

    override suspend fun isBookmarked(geohash: String): Boolean = withContext(coroutinesContextFacade.io) {
        bookmarkPrefs.isBookmarked(geohash)
    }

    override suspend fun registerSelfAsParticipant(
        geohash: String,
        nickname: String,
        isTeleported: Boolean
    ) = withContext(coroutinesContextFacade.io) {
        this@LocationRepo.isTeleported = isTeleported
        participantTracker.setCurrentGeohash(geohash)
        addSelfAsParticipant(geohash, isTeleported, nickname)
        locationEventBus.update(com.bitchat.domain.location.model.LocationEvent.ParticipantsChanged)
    }

    override suspend fun unregisterSelfFromCurrentGeohash() = withContext(coroutinesContextFacade.io) {
        participantTracker.setCurrentGeohash(null)
        locationEventBus.update(com.bitchat.domain.location.model.LocationEvent.ParticipantsChanged)
    }

    override suspend fun isTeleported(): Boolean = withContext(coroutinesContextFacade.io) {
        isTeleported
    }

    private suspend fun addSelfAsParticipant(geohash: String, isTeleported: Boolean, nickname: String): Unit =
        withContext(coroutinesContextFacade.io) {
            val identity = try {
                nostrClient.deriveIdentity(geohash)
            } catch (_: Exception) {
                return@withContext
            }

            logNostrDebug(
                "LocationRepo",
                "Registering self on geohash=$geohash pubkey=${identity.publicKeyHex.take(16)}... nickname=$nickname teleported=$isTeleported"
            )

            participantTracker.updateParticipant(
                geohash = geohash,
                pubkey = identity.publicKeyHex,
                nickname = nickname,
                timestamp = Clock.System.now(),
                isTeleported = isTeleported
            )
        }

    private fun handleGeohashSamplingEvent(geohash: String, event: com.bitchat.nostr.model.NostrEvent) {
        println("📍 LocationRepo.handleGeohashSamplingEvent: geohash=$geohash, eventId=${event.id.take(16)}..., hasContent=${event.content.isNotBlank()}")

        val tagGeo = event.tags.firstOrNull { it.size >= 2 && it[0] == "g" }?.getOrNull(1)
        if (tagGeo == null || !tagGeo.equals(geohash, ignoreCase = true)) {
            println("   ⚠️ LocationRepo: Geohash tag mismatch, skipping")
            return
        }
        val nickname = event.tags.firstOrNull { it.size >= 2 && it[0] == "n" }?.getOrNull(1) ?: "anon"
        val isTeleported = event.tags.any { it.size >= 2 && it[0] == "t" && it[1] == "teleport" }
        logNostrDebug(
            "LocationRepo",
            "Sampling event geohash=$geohash sender=${event.pubkey.take(16)}... nickname=$nickname teleported=$isTeleported"
        )

        coroutineScopeFacade.nostrScope.launch {
            participantTracker.updateParticipant(
                geohash = geohash,
                pubkey = event.pubkey,
                nickname = nickname,
                timestamp = Instant.fromEpochSeconds(event.createdAt.toLong()),
                isTeleported = isTeleported
            )
            println("   ✅ LocationRepo: Participant tracker updated for ${nickname}")
        }
    }

    override suspend fun toggleLocationServices() = withContext(coroutinesContextFacade.io) {
        val isEnabled = geohashPreferences.getLocationServicesEnabled()
        geohashPreferences.saveLocationServicesEnabled(!isEnabled)
    }

    override suspend fun isLocationServicesEnabled(): Boolean = withContext(coroutinesContextFacade.io) {
        geohashPreferences.getLocationServicesEnabled()
    }

    override suspend fun getPermissionState(): PermissionState = withContext(coroutinesContextFacade.io) {
        if (locationService.hasLocationPermission()) {
            PermissionState.AUTHORIZED
        } else {
            PermissionState.DENIED
        }
    }

    override suspend fun requestLocationPermission() = withContext(coroutinesContextFacade.io) {
        locationService.requestLocationPermission()
    }

    override suspend fun hasNotes(geohash: String): Boolean = withContext(coroutinesContextFacade.io) {
        // TODO: Implement actual notes checking when notes storage is implemented
        // For now, return false since getNotes() is also TODO
        false
    }

    override suspend fun resolveLocationName(geohash: String, level: GeohashChannelLevel): String? =
        withContext(coroutinesContextFacade.io) {
            try {
                val fix = lastFix?.takeIf {
                    GeohashUtils.encode(it.lat, it.lon, level.precision) == geohash
                }
                val (lat, lon) = if (fix != null) {
                    fix.lat to fix.lon
                } else {
                    GeohashUtils.decodeToCenter(geohash)
                }
                val name = geocoder.reverseGeocode(lat, lon, level).trim()
                if (name.isBlank()) {
                    return@withContext null
                }

                synchronized(locationNamesLock) {
                    locationNames[level] = name
                }

                name
            } catch (e: Exception) {
                null
            }
        }

    override suspend fun getLocationNames(): Map<GeohashChannelLevel, String> = withContext(coroutinesContextFacade.io) {
        synchronized(locationNamesLock) {
            locationNames.toMap()
        }
    }

    private suspend fun resolveLocationNamesForFix(fix: GeoPoint) {
        val neighborhoodHash = GeohashUtils.encode(
            fix.lat,
            fix.lon,
            GeohashChannelLevel.NEIGHBORHOOD.precision
        )
        val hasNames = synchronized(locationNamesLock) {
            locationNames.isNotEmpty()
        }
        if (lastResolvedGeohash == neighborhoodHash && hasNames) {
            return
        }

        val names = geocoder.reverseGeocodeAll(fix.lat, fix.lon)
        if (names.isNotEmpty()) {
            synchronized(locationNamesLock) {
                locationNames.clear()
                locationNames.putAll(names)
            }
            lastResolvedGeohash = neighborhoodHash
        }
    }

    override suspend fun clearData() = withContext(coroutinesContextFacade.io) {
        isTeleported = false
        synchronized(locationNamesLock) {
            locationNames.clear()
        }
        lastFix = null
        lastResolvedGeohash = null
        synchronized(samplingLock) {
            samplingSubscriptions.clear()
            sampledGeohashes.clear()
        }

        bookmarkPrefs.clearAllBookmarks()
        geohashPreferences.saveLocationServicesEnabled(false)
        participantTracker.setCurrentGeohash(null)
    }
}
