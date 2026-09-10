package com.bitchat.local.service

import com.bitchat.domain.location.model.GeoPoint
import com.bitchat.domain.location.model.LocationFixInfo
import com.bitchat.domain.location.model.LocationUnavailableException
import com.bitchat.domain.tor.RequestedTorIntent
import com.bitchat.domain.tor.model.TorMode
import com.bitchat.local.bridge.NativeLocationBridge
import com.russhwolf.settings.Settings
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

/**
 * Desktop location: the operating system where there is one, otherwise a coarse guess from the
 * public IP address, otherwise the last fix we managed to get.
 *
 * An empty channel list is a dead end for the user -- there is nothing to join and nothing to do
 * about it -- so an approximate answer they can correct by hand beats no answer. What this must not
 * do is invent one: the reading always comes from a real lookup or a real earlier lookup, and when
 * there is none the caller is told so rather than handed a hardcoded city.
 */
class JvmLocationService(
    settingsFactory: Settings.Factory,
    private val requestedTorIntent: RequestedTorIntent,
    private val ipLookup: suspend (admit: () -> Boolean) -> GeoPoint? = IpGeolocation::lookup,
) : LocationService {

    private val settings = settingsFactory.create(PREFS_NAME)
    private val lookupLock = Mutex()

    private val nativeEnabled = System.getProperty("location.native")?.lowercase() == "macos"
    private val nativeAvailable = if (nativeEnabled) {
        println("JvmLocationService: native mode enabled, initializing...")
        NativeLocationBridge.init()
    } else {
        println("JvmLocationService: no native location source, may use IP geolocation")
        false
    }

    /**
     * Whether an IP lookup may run at all.
     *
     * Keyed on what the user asked for, not on whether Tor managed to start. An IP lookup must
     * bypass the proxy to mean anything -- through Tor it would report the exit node's city -- so
     * it is inherently a request that discloses the user's address, and a user who has asked for
     * Tor has asked for exactly that not to happen. The effective mode is the wrong signal here:
     * it reads OFF whenever Tor failed to start, which is precisely when the user's stated wish
     * still stands and this must stay closed.
     *
     * This was previously a system property that nothing in the tree ever set, so the gate was
     * permanently open.
     */
    private fun ipLookupAdmitted(): Boolean =
        requestedTorIntent.current == TorMode.OFF &&
            System.getProperty("location.iplookup")?.lowercase() != "false"

    @Volatile
    private var cachedFix: GeoPoint? = null

    @Volatile
    private var observedAtMs: Long = 0

    @Volatile
    private var fixSource: LocationFixInfo.Source? = null

    @Volatile
    private var cachedAtMs: Long = 0

    @Volatile
    private var lastAttemptMs: Long = 0

    /**
     * Bumped whenever the cache is cleared. A lookup captures it before going to the network and
     * discards its answer if it changed, so a request already in flight when the user wipes their
     * data cannot write the coordinate back afterwards.
     */
    private val generation = AtomicLong()

    override suspend fun getCurrentLocation(): GeoPoint {
        if (nativeAvailable) {
            NativeLocationBridge.getCurrentLocation()?.let { (lat, lon) ->
                println("JvmLocationService: got native location")
                val fix = GeoPoint(lat, lon)
                remember(fix, LocationFixInfo.Source.DEVICE)
                return fix
            }
            println("JvmLocationService: native location lookup failed")
        }

        /*
         * The channel sheet refreshes every five seconds and each refresh asks for a fix, so
         * without this the app made twelve requests a minute to a third-party endpoint for a
         * reading that changes by nothing. That is what the previous implementation did.
         */
        cachedFix?.let { fix ->
            if (now() - cachedAtMs < FRESH_FOR_MS) return fix
        }

        if (ipLookupAdmitted()) {
            lookupLock.withLock {
                // Another caller may have refreshed it while this one waited for the lock.
                cachedFix?.let { fix ->
                    if (now() - cachedAtMs < FRESH_FOR_MS) return fix
                }
                // Offline, this would otherwise retry three providers every five seconds.
                if (now() - lastAttemptMs >= RETRY_AFTER_MS) {
                    lastAttemptMs = now()
                    val startedAt = generation.get()
                    ipLookup(::ipLookupAdmitted)?.let { fix ->
                        if (generation.get() != startedAt) {
                            println("JvmLocationService: discarding fix, cache cleared while looking up")
                            return@withLock
                        }
                        remember(fix, LocationFixInfo.Source.IP_ADDRESS)
                        return fix
                    }
                }
            }
        }

        /*
         * Stale, but a real place the user was, which beats an empty list. Survives restarts so a
         * cold launch with no network still has channels to show.
         */
        (cachedFix ?: readStoredFix())?.let { fix ->
            println("JvmLocationService: no fresh fix, using last known")
            return fix
        }

        throw LocationUnavailableException(
            // Said plainly, because "not available on this device" would be a lie: the source
            // exists and was declined, and the user can get their channels back by turning Tor off
            // or by entering a geohash by hand.
            if (!nativeAvailable && !ipLookupAdmitted()) {
                LocationUnavailableException.Reason.SUPPRESSED_BY_POLICY
            } else {
                LocationUnavailableException.Reason.LOOKUP_FAILED
            }
        )
    }

    override fun locationUpdates() = kotlinx.coroutines.flow.flow {
        emit(getCurrentLocation())
    }

    /*
     * Reported as granted because on this platform nothing is withholding permission, and offering
     * "open settings" would send the user somewhere that cannot help. Whether a fix is actually
     * obtainable is answered by getCurrentLocation() instead, which is the distinction the location
     * sheet renders.
     */
    override suspend fun hasLocationPermission(): Boolean {
        return if (nativeAvailable) NativeLocationBridge.hasPermission() else true
    }

    override suspend fun requestLocationPermission() {
        if (nativeAvailable) {
            NativeLocationBridge.requestPermission()
        }
    }

    override suspend fun lastFixInfo(): LocationFixInfo? {
        if (cachedFix == null) readStoredFix()
        val source = fixSource ?: return null
        return LocationFixInfo(source, ageMillis = (now() - observedAtMs).coerceAtLeast(0))
    }

    /**
     * Forgets the fix in memory and on disk.
     *
     * [generation] is bumped first so a lookup already in flight cannot call [remember] afterwards
     * and quietly recreate the record that was just deleted.
     */
    override suspend fun clearCachedLocation() {
        // Deliberately does not take lookupLock: a lookup holds it across the network call, so
        // waiting would block clear-all-data for as long as three providers take to time out.
        generation.incrementAndGet()
        cachedFix = null
        fixSource = null
        observedAtMs = 0
        cachedAtMs = 0
        lastAttemptMs = 0
        settings.remove(KEY_LAT)
        settings.remove(KEY_LON)
        settings.remove(KEY_OBSERVED_AT)
        settings.remove(KEY_SOURCE)
        println("JvmLocationService: cached location cleared")
    }

    private fun remember(fix: GeoPoint, source: LocationFixInfo.Source, atMs: Long = now()) {
        cachedFix = fix
        fixSource = source
        observedAtMs = atMs
        cachedAtMs = atMs
        settings.putDouble(KEY_LAT, fix.lat)
        settings.putDouble(KEY_LON, fix.lon)
        settings.putLong(KEY_OBSERVED_AT, atMs)
        settings.putString(KEY_SOURCE, source.name)
    }

    private fun readStoredFix(): GeoPoint? {
        val lat = settings.getDoubleOrNull(KEY_LAT) ?: return null
        val lon = settings.getDoubleOrNull(KEY_LON) ?: return null
        val source = settings.getStringOrNull(KEY_SOURCE)
            ?.let { name -> LocationFixInfo.Source.entries.firstOrNull { it.name == name } }
            ?: LocationFixInfo.Source.IP_ADDRESS
        /*
         * cachedAtMs is deliberately NOT set here. It gates the 15-minute freshness window, and a
         * fix restored from disk must not count as fresh -- otherwise a restart inside the window
         * would serve a coordinate from a previous session without ever asking again.
         */
        fixSource = source
        observedAtMs = settings.getLongOrNull(KEY_OBSERVED_AT) ?: 0L
        return GeoPoint(lat, lon).also { cachedFix = it }
    }

    private fun now() = System.currentTimeMillis()

    private companion object {
        const val PREFS_NAME = "location_prefs"
        const val KEY_LAT = "last_fix_lat"
        const val KEY_LON = "last_fix_lon"
        const val KEY_OBSERVED_AT = "last_fix_observed_at"
        const val KEY_SOURCE = "last_fix_source"

        /** An IP-derived fix is city-level, so re-asking sooner than this cannot tell us anything. */
        const val FRESH_FOR_MS = 15 * 60 * 1000L

        /** Backs off after a failed lookup so an offline machine is not retrying constantly. */
        const val RETRY_AFTER_MS = 60 * 1000L
    }
}
