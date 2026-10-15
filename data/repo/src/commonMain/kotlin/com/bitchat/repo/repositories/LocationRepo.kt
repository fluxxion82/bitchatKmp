package com.bitchat.repo.repositories

import com.bitchat.repo.utils.RepeatedLogLimiter
import com.bitchat.domain.base.CoroutineScopeFacade
import com.bitchat.domain.base.CoroutinesContextFacade
import com.bitchat.domain.location.eventbus.LocationEventBus
import com.bitchat.domain.location.model.DEFAULT_PLACE
import com.bitchat.domain.location.model.GeoPerson
import com.bitchat.domain.location.model.GeoPoint
import com.bitchat.domain.location.model.GeohashChannel
import com.bitchat.domain.location.model.LocationFixInfo
import com.bitchat.domain.location.model.LocationUnavailableException
import com.bitchat.domain.location.model.GeohashChannelLevel
import com.bitchat.domain.location.model.NearbyChannels
import com.bitchat.domain.location.model.Note
import com.bitchat.domain.location.model.PermissionState
import com.bitchat.domain.location.model.SavedPlace
import com.bitchat.domain.location.repository.LocationRepository
import com.bitchat.local.prefs.BookmarkPreferences
import com.bitchat.local.prefs.GeohashPreferences
import com.bitchat.local.service.GeocoderService
import com.bitchat.local.service.LocationService
import com.bitchat.nostr.NostrClient
import com.bitchat.nostr.NostrPreferences
import com.bitchat.nostr.NostrRelay
import com.bitchat.nostr.NostrSubscriptionId
import com.bitchat.nostr.logging.logNostrDebug
import com.bitchat.nostr.model.NostrFilter
import com.bitchat.nostr.participant.NostrParticipantTracker
import com.bitchat.nostr.util.GeohashUtils
import com.bitchat.repo.location.resolveBookmarkName
import com.bitchat.repo.location.isPresent
import com.bitchat.repo.mappers.toDomain
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
    private val explicitTeleports = LinkedHashSet<String>()
    private val explicitTeleportLock = SynchronizedObject()
    private val locationNames = mutableMapOf<GeohashChannelLevel, String>()
    private val locationNamesLock = SynchronizedObject()
    private var lastFix: GeoPoint? = null
    private val locationGeneration = atomic(0L)

    // A load's "still current?" and the write it guards are one step with every change of place.
    private val placeLock = SynchronizedObject()

    private inline fun ifCurrent(generation: Long, write: () -> Unit) = synchronized(placeLock) {
        if (generation == locationGeneration.value) write()
    }

    /** The five-second poll's "no location fix" line, let through once per reason and ten minutes. */
    private val noFixLog = RepeatedLogLimiter(intervalMillis = 10 * 60_000L)
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
                val subId = NostrSubscriptionId.notes(geoHash, index)
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
            savedPlace()?.let { return@withContext it.at(level) }
            // A place saved while the platform answered wins over that answer, or its failure.
            val fix = try {
                locationService.getCurrentLocation()
            } catch (e: LocationUnavailableException) {
                savedPlace()?.let { return@withContext it.at(level) }
                throw e
            }
            savedPlace()?.let { return@withContext it.at(level) }
            GeohashUtils.encode(fix.lat, fix.lon, level.precision)
        }

    private fun SavedPlace.at(level: GeohashChannelLevel): String =
        if (geohash.length >= level.precision) geohash.take(level.precision)
        else throw LocationUnavailableException(LocationUnavailableException.Reason.NO_SOURCE)

    override suspend fun getSavedPlace(): SavedPlace? = withContext(coroutinesContextFacade.io) { savedPlace() }

    override suspend fun setSavedPlace(place: SavedPlace) = withContext(coroutinesContextFacade.io) {
        synchronized(placeLock) {
            geohashPreferences.saveSavedPlace(place.geohash)
            forgetPlaceLocked()
        }
    }

    override suspend fun clearSavedPlace() = withContext(coroutinesContextFacade.io) {
        synchronized(placeLock) {
            geohashPreferences.clearSavedPlace()
            forgetPlaceLocked()
        }
    }

    private fun forgetPlace() = synchronized(placeLock) { forgetPlaceLocked() }

    private fun forgetPlaceLocked() {
        locationGeneration.incrementAndGet()
        synchronized(locationNamesLock) {
            locationNames.clear()
            lastFix = null
            lastResolvedGeohash = null
        }
    }

    override suspend fun getAvailableGeohashChannels(): NearbyChannels = withContext(coroutinesContextFacade.io) {
        val generation = locationGeneration.value
        try {
            if (!geohashPreferences.getLocationServicesEnabled()) return@withContext NearbyChannels(emptyList(), null)
            savedPlace()?.let { place ->
                val geohash = place.geohash
                val fix = GeohashUtils.decodeToCenterOrNull(geohash)
                if (fix != null) {
                    val point = GeoPoint(fix.first, fix.second)
                    ifCurrent(generation) {
                        lastFix = point
                    }
                    resolveLocationNamesForFix(point, generation)
                }
                val levels = listOf(
                    GeohashChannelLevel.BLOCK,
                    GeohashChannelLevel.NEIGHBORHOOD,
                    GeohashChannelLevel.CITY,
                    GeohashChannelLevel.PROVINCE,
                    GeohashChannelLevel.REGION
                )
                return@withContext NearbyChannels(
                    levels.filter { it.precision <= geohash.length }.map { level ->
                        GeohashChannel(level, geohash.take(level.precision))
                    },
                    LocationFixInfo(LocationFixInfo.Source.MANUAL, ageMillis = 0)
                )
            }
            // This call's own outcome stays local while the sheet uses its fallback place.
            val (fix, fixInfo) = try {
                val real = locationService.getCurrentLocation()
                real to locationService.lastFixInfo()
            } catch (e: LocationUnavailableException) {
                // The sheet polls every five seconds, so no stack trace and a rate-limited line.
                noFixLog.next("no location fix: ${e.reason.message}; using the default place")
                    ?.let { logNostrDebug("LocationRepo", it) }
                DEFAULT_PLACE to LocationFixInfo(
                    LocationFixInfo.Source.DEFAULT,
                    ageMillis = 0,
                    unavailableReason = e.reason
                )
            }
            ifCurrent(generation) {
                lastFix = fix
            }
            resolveLocationNamesForFix(fix, generation)

            val levels = listOf(
                GeohashChannelLevel.BLOCK,
                GeohashChannelLevel.NEIGHBORHOOD,
                GeohashChannelLevel.CITY,
                GeohashChannelLevel.PROVINCE,
                GeohashChannelLevel.REGION
            )

            NearbyChannels(
                levels.map { level ->
                    val geohash = GeohashUtils.encode(fix.lat, fix.lon, level.precision)
                    GeohashChannel(
                        level = level,
                        geohash = geohash
                    )
                },
                fixInfo
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            NearbyChannels(emptyList(), null)
        }
    }

    override suspend fun getBookmarkedChannel(): List<GeohashChannel> = withContext(coroutinesContextFacade.io) {
        TODO("Not yet implemented")
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
            val subId = NostrSubscriptionId.sampling(geohash)
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
        val normalized = SavedPlace.of(geohash)?.geohash
        synchronized(explicitTeleportLock) {
            this@LocationRepo.isTeleported = isTeleported
            if (isTeleported && normalized != null && explicitTeleports.add(normalized)) {
                while (explicitTeleports.size > MAX_EXPLICIT_TELEPORTS) {
                    explicitTeleports.remove(explicitTeleports.first())
                }
            }
        }
        participantTracker.setCurrentGeohash(geohash)
        addSelfAsParticipant(geohash, isTeleported, nickname)
        locationEventBus.update(com.bitchat.domain.location.model.LocationEvent.ParticipantsChanged)
    }

    override suspend fun unregisterSelfFromCurrentGeohash() = withContext(coroutinesContextFacade.io) {
        participantTracker.setCurrentGeohash(null)
        locationEventBus.update(com.bitchat.domain.location.model.LocationEvent.ParticipantsChanged)
    }

    override suspend fun isTeleported(): Boolean = withContext(coroutinesContextFacade.io) {
        synchronized(explicitTeleportLock) { isTeleported }
    }

    override suspend fun isTeleportedIn(geohash: String): Boolean = withContext(coroutinesContextFacade.io) {
        val normalized = SavedPlace.of(geohash)?.geohash
        if (normalized != null && synchronized(explicitTeleportLock) { normalized in explicitTeleports }) {
            return@withContext true
        }

        val snapshot = synchronized(placeLock) {
            PresenceSnapshot(
                generation = locationGeneration.value,
                servicesEnabled = geohashPreferences.getLocationServicesEnabled(),
                savedPlace = savedPlace(),
            )
        }
        if (!snapshot.servicesEnabled) return@withContext true
        val present = snapshot.savedPlace?.let { isPresent(geohash, it, null) } ?: run {
            val fix = try {
                withTimeoutOrNull(PRESENCE_READ_LIMIT) { locationService.presenceFix() }
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                return@withContext true
            }
            isPresent(geohash, null, fix)
        }
        if (!present) return@withContext true
        // A change of place or of the services switch during this check withdraws its evidence.
        if (synchronized(placeLock) { locationGeneration.value != snapshot.generation }) {
            return@withContext true
        }
        normalized != null && synchronized(explicitTeleportLock) { normalized in explicitTeleports }
    }

    private fun savedPlace(): SavedPlace? = geohashPreferences.getSavedPlace()?.let(SavedPlace::of)

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
        synchronized(placeLock) {
            val isEnabled = geohashPreferences.getLocationServicesEnabled()
            geohashPreferences.saveLocationServicesEnabled(!isEnabled)
            locationGeneration.incrementAndGet()
        }
        Unit
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
                // Two places can share a coarse prefix, so the prefix alone cannot tell a stale answer.
                val generation = locationGeneration.value
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
                    if (generation == locationGeneration.value &&
                        lastFix?.let { GeohashUtils.encode(it.lat, it.lon, level.precision) } == geohash
                    ) {
                        locationNames[level] = name
                    }
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

    private suspend fun resolveLocationNamesForFix(fix: GeoPoint, generation: Long) {
        if (generation != locationGeneration.value) return
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

        val names = try {
            geocoder.reverseGeocodeAll(fix.lat, fix.lon)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return
        }
        if (names.isNotEmpty()) {
            synchronized(locationNamesLock) {
                if (generation == locationGeneration.value && lastFix == fix) {
                    locationNames.clear()
                    locationNames.putAll(names)
                    lastResolvedGeohash = neighborhoodHash
                }
            }
        }
    }

    override suspend fun clearData() = withContext(coroutinesContextFacade.io) {
        bookmarkPrefs.clearAllBookmarks()
        synchronized(placeLock) {
            geohashPreferences.clearSavedPlace()
            geohashPreferences.saveLocationServicesEnabled(false)
            forgetPlaceLocked()
        }
        synchronized(explicitTeleportLock) {
            isTeleported = false
            explicitTeleports.clear()
        }
        synchronized(samplingLock) {
            samplingSubscriptions.clear()
            sampledGeohashes.clear()
        }
        participantTracker.setCurrentGeohash(null)

        /*
         * The service keeps its own cache, in memory and on disk, so clearing repository state left
         * the user's last coordinates recoverable after an operation that says it deletes
         * everything. Desktop persists them; other platforms default this to a no-op.
         */
        locationService.clearCachedLocation()
    }

    private data class PresenceSnapshot(
        val generation: Long,
        val servicesEnabled: Boolean,
        val savedPlace: SavedPlace?,
    )

    private companion object {
        const val MAX_EXPLICIT_TELEPORTS = 256
        val PRESENCE_READ_LIMIT = 3.seconds
    }
}
