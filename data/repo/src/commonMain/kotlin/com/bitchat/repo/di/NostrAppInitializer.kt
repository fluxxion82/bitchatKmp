package com.bitchat.repo.di

import com.bitchat.cache.Cache
import com.bitchat.client.NostrGeoRelayClient
import com.bitchat.domain.base.CoroutineScopeFacade
import com.bitchat.domain.base.model.isSuccess
import com.bitchat.domain.initialization.AppInitializer
import com.bitchat.nostr.NostrPreferences
import com.bitchat.nostr.ResourceReader
import com.bitchat.nostr.model.RelayInfo
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.internal.SynchronizedObject
import kotlinx.coroutines.internal.synchronized
import kotlinx.coroutines.launch
import kotlin.time.Clock

private const val ASSET_FILE = "nostr_relays.csv"
private const val DOWNLOADED_FILE = "nostr_relays_latest.csv"
private const val ONE_DAY_MS = 24 * 60 * 60 * 1000L

class NostrAppInitializer(
    private val nostrGeoRelayClient: NostrGeoRelayClient,
    private val resourceReader: ResourceReader,
    private val relayPreferences: NostrPreferences,
    private val coroutineScopeFacade: CoroutineScopeFacade,
    private val relayCache: Cache<String, RelayInfo>,
) : AppInitializer {
    @OptIn(InternalCoroutinesApi::class)
    private val relaysLock = SynchronizedObject()

    override suspend fun initialize() {
        coroutineScopeFacade.nostrScope.launch {
            val fileBytes = resourceReader.readFile(DOWNLOADED_FILE)
            val loadedFromDownloaded = if (fileBytes?.isNotEmpty() == true) {
                loadFromBytes(fileBytes)
            } else {
                false
            }

            if (!loadedFromDownloaded) {
                loadFromResources()
            }

            startPeriodicRefresh()
        }
    }

    @OptIn(InternalCoroutinesApi::class)
    private fun loadFromBytes(bytes: ByteArray): Boolean {
        return try {
            val list = parseCsv(bytes)
            if (list.isEmpty()) {
                false
            } else {
                synchronized(relaysLock) {
                    relayCache.removeAll()
                    list.forEach {
                        relayCache[it.url] = it
                    }
                }
                true
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    @OptIn(InternalCoroutinesApi::class)
    private fun loadFromResources() {
        val bytes = resourceReader.readResourceFile(ASSET_FILE)
        val list = if (bytes != null) {
            try {
                parseCsv(bytes)
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }

        synchronized(relaysLock) {
            relayCache.removeAll()
            list.forEach {
                relayCache[it.url] = it
            }
        }
    }

    @OptIn(InternalCoroutinesApi::class)
    private suspend fun fetchAndMaybeSwap() {
        try {
            val downloadedBytesOutcome = nostrGeoRelayClient.downloadToFile()
            if (!downloadedBytesOutcome.isSuccess()) {
                return
            }

            val downloadedBytes = downloadedBytesOutcome.value
            val parsed = parseCsv(downloadedBytes)
            if (parsed.isEmpty()) {
                return
            }

            val writeSuccess = resourceReader.writeFile(downloadedBytes, DOWNLOADED_FILE)
            if (!writeSuccess) {
                return
            }

            synchronized(relaysLock) {
                relayCache.removeAll()
                parsed.forEach {
                    relayCache[it.url] = it
                }
            }

            relayPreferences.setLastUpdateMs(Clock.System.now().toEpochMilliseconds())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun isStale(): Boolean {
        val last = relayPreferences.getLastUpdateMs()
        val now = Clock.System.now().toEpochMilliseconds()
        return now - last >= ONE_DAY_MS
    }

    private fun startPeriodicRefresh() {
        coroutineScopeFacade.nostrScope.launch {
            while (true) {
                try {
                    if (isStale()) {
                        fetchAndMaybeSwap()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                delay(60_000L)
            }
        }
    }

    private fun normalizeRelayUrl(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return trimmed
        return if ("://" in trimmed) trimmed else "wss://$trimmed"
    }

    private fun parseCsv(bytes: ByteArray): List<RelayInfo> {
        val result = mutableListOf<RelayInfo>()
        val content = bytes.decodeToString()
        content.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEach
            if (trimmed.lowercase().startsWith("relay url")) return@forEach

            val parts = trimmed.split(",")
            if (parts.size < 3) return@forEach

            val url = normalizeRelayUrl(parts[0].trim())
            val lat = parts[1].trim().toDoubleOrNull()
            val lon = parts[2].trim().toDoubleOrNull()

            if (url.isEmpty() || lat == null || lon == null) return@forEach
            result.add(RelayInfo(url = url, latitude = lat, longitude = lon))
        }
        return result
    }
}
