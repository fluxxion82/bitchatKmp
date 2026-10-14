package com.bitchat.local.service

import com.bitchat.domain.location.model.GeoPoint
import com.bitchat.domain.location.model.LocationFixInfo
import com.bitchat.domain.location.model.LocationUnavailableException
import com.bitchat.domain.tor.RequestedTorIntent
import com.bitchat.domain.tor.model.TorMode
import com.russhwolf.settings.Settings
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource

internal class IpLocationSource(
    settingsFactory: Settings.Factory,
    private val requestedTorIntent: RequestedTorIntent,
    private val ipLookup: suspend (admit: () -> Boolean) -> GeoPoint?,
    private val lookupEnabled: () -> Boolean = { true },
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val wallClock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val settings = settingsFactory.create(PREFS_NAME)
    private val lookupLock = Mutex()
    private val stateLock = SynchronizedObject()
    private val generation = atomic(0L)

    @Volatile
    private var cachedFix: GeoPoint? = null
    @Volatile
    private var observedAtMs = 0L
    @Volatile
    private var fixSource: LocationFixInfo.Source? = null
    @Volatile
    private var cachedAt: TimeMark? = null
    @Volatile
    private var lastAttemptAt: TimeMark? = null

    fun lookupAdmitted(): Boolean = requestedTorIntent.current == TorMode.OFF && lookupEnabled()

    suspend fun fix(): GeoPoint? {
        freshFix()?.let { return it }

        if (lookupAdmitted()) {
            lookupLock.withLock {
                freshFix()?.let { return it }
                if (lastAttemptAt?.elapsedNow()?.let { it < RETRY_AFTER } != true) {
                    lastAttemptAt = timeSource.markNow()
                    val startedAt = generation.value
                    ipLookup(::lookupAdmitted)?.let { fix ->
                        if (rememberUnlessCleared(startedAt, fix, LocationFixInfo.Source.IP_ADDRESS)) return fix
                    }
                }
            }
        }

        return cachedFix ?: readStoredFix()
    }

    /** The fix [read] gives, kept unless the store was cleared while it was read. */
    suspend fun rememberDeviceFix(read: suspend () -> GeoPoint?): GeoPoint? {
        val startedAt = generation.value
        return read()?.also { rememberUnlessCleared(startedAt, it, LocationFixInfo.Source.DEVICE) }
    }

    // One step with clear(): a fix read before a clear is never written after it.
    private fun rememberUnlessCleared(
        startedAt: Long,
        fix: GeoPoint,
        source: LocationFixInfo.Source,
    ): Boolean = synchronized(stateLock) {
        if (generation.value != startedAt) return false
        cachedFix = fix
        fixSource = source
        observedAtMs = wallClock()
        cachedAt = timeSource.markNow()
        settings.putDouble(KEY_LAT, fix.lat)
        settings.putDouble(KEY_LON, fix.lon)
        settings.putLong(KEY_OBSERVED_AT, observedAtMs)
        settings.putString(KEY_SOURCE, source.name)
        true
    }

    suspend fun lastFixInfo(): LocationFixInfo? {
        if (cachedFix == null) readStoredFix()
        val source = fixSource ?: return null
        return LocationFixInfo(source, (wallClock() - observedAtMs).coerceAtLeast(0))
    }

    fun clear() {
        synchronized(stateLock) {
            generation.incrementAndGet()
            cachedFix = null
            fixSource = null
            observedAtMs = 0
            cachedAt = null
            lastAttemptAt = null
            settings.remove(KEY_LAT)
            settings.remove(KEY_LON)
            settings.remove(KEY_OBSERVED_AT)
            settings.remove(KEY_SOURCE)
        }
    }

    fun unavailableReason(hasDeviceSource: Boolean): LocationUnavailableException.Reason =
        if (!hasDeviceSource && !lookupAdmitted()) {
            LocationUnavailableException.Reason.SUPPRESSED_BY_POLICY
        } else {
            LocationUnavailableException.Reason.LOOKUP_FAILED
        }

    private fun freshFix(): GeoPoint? = cachedFix?.takeIf {
        cachedAt?.elapsedNow()?.let { age -> age < FRESH_FOR } == true
    }

    private fun readStoredFix(): GeoPoint? = synchronized(stateLock) {
        // A fix from disk must not count as fresh, or a restart would never ask again.
        val lat = settings.getDoubleOrNull(KEY_LAT) ?: return null
        val lon = settings.getDoubleOrNull(KEY_LON) ?: return null
        val source = settings.getStringOrNull(KEY_SOURCE)
            ?.let { name -> LocationFixInfo.Source.entries.firstOrNull { it.name == name } }
            ?: LocationFixInfo.Source.IP_ADDRESS
        fixSource = source
        observedAtMs = settings.getLongOrNull(KEY_OBSERVED_AT) ?: 0L
        return GeoPoint(lat, lon).also { cachedFix = it }
    }

    private companion object {
        const val PREFS_NAME = "location_prefs"
        const val KEY_LAT = "last_fix_lat"
        const val KEY_LON = "last_fix_lon"
        const val KEY_OBSERVED_AT = "last_fix_observed_at"
        const val KEY_SOURCE = "last_fix_source"
        val FRESH_FOR = 15.minutes
        val RETRY_AFTER = 1.minutes
    }
}
