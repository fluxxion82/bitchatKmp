package com.bitchat.nostr

import com.bitchat.cache.Cache
import com.bitchat.client.websocket.NostrWebSocketClient
import com.bitchat.client.websocket.NostrWebSocketListener
import com.bitchat.nostr.model.NostrEvent
import com.bitchat.nostr.model.NostrFilter
import com.bitchat.nostr.model.NostrKind
import com.bitchat.nostr.model.NostrResponse
import com.bitchat.nostr.model.RelayInfo
import com.bitchat.nostr.util.ConcurrentMap
import com.bitchat.nostr.util.GeohashUtils
import com.bitchat.nostr.util.GeohashUtils.haversineDistance
import com.bitchat.nostr.util.NostrEventDeduplicator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.internal.SynchronizedObject
import kotlinx.coroutines.internal.synchronized
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.time.Clock

// Relay subscription validation
const val SUBSCRIPTION_VALIDATION_INTERVAL_MS: Long = 30_000L
private val DEFAULT_RELAYS = listOf(
    "wss://relay.damus.io",
    "wss://relay.primal.net",
    "wss://offchain.pub",
    "wss://nostr21.com"
)

@OptIn(InternalCoroutinesApi::class)
class NostrRelay(
    private val eventDeduplicator: NostrEventDeduplicator,
    private val wsClient: NostrWebSocketClient,
    private val relayCache: Cache<String, RelayInfo>,
    private val relayLogSink: RelayLogSink? = null,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val pendingGiftWrapIDs = HashSet<String>()

    // Internal state
    private val relaysList = mutableListOf<Relay>()
    private val subscriptions = ConcurrentMap<String, Set<String>>() // relay URL -> subscription IDs
    private val messageHandlers = ConcurrentMap<String, MutableList<(NostrEvent) -> Unit>>()

    // Persistent subscription tracking for robust reconnection
    private val activeSubscriptions = ConcurrentMap<String, SubscriptionInfo>()

    // Message queue for reliability with per-relay delivery tracking
    private data class QueuedMessage(
        val event: NostrEvent,
        val targetRelays: Set<String>,
        val deliveredTo: MutableSet<String> = mutableSetOf()
    )

    private val messageQueue = mutableListOf<QueuedMessage>()
    private val messageQueueLock = SynchronizedObject()

    // Coroutine scope for background operations
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Subscription validation timer
    private var subscriptionValidationJob: Job? = null
    private val SUBSCRIPTION_VALIDATION_INTERVAL = SUBSCRIPTION_VALIDATION_INTERVAL_MS // 30 seconds

    // Per-geohash relay selection
    private val geohashToRelays = ConcurrentMap<String, Set<String>>() // geohash -> relay URLs

    /**
     * Ensure default relays are connected (used for global DMs).
     */
    fun ensureDefaultRelaysConnected() {
        val selected = DEFAULT_RELAYS.toSet()
        if (selected.isEmpty()) return

        selected.forEach { url ->
            if (relaysList.none { it.url == url }) {
                relaysList.add(Relay(url))
            }
        }

        ensureConnectionsFor(selected)
    }

    /**
     * Compute and connect to relays for a given geohash (nearest + optional defaults), cache the mapping.
     */
    fun ensureGeohashRelaysConnected(geohash: String, nRelays: Int = 5, includeDefaults: Boolean = false) {
        try {
            println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            println("🔗 NostrRelay.ensureGeohashRelaysConnected")
            println("   Geohash: $geohash")
            println("   Requested relays: $nRelays")
            println("   Include defaults: $includeDefaults")
            println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

            val nearest = closestRelaysForGeohash(geohash, nRelays)
            println("📍 NostrRelay: Found ${nearest.size} nearest relays for geohash=$geohash")
            nearest.forEachIndexed { index, url ->
                println("   ${index + 1}. $url")
            }

            val selected = if (includeDefaults) {
                val combined = (nearest + DEFAULT_RELAYS).toSet()
                println("📋 NostrRelay: Including ${DEFAULT_RELAYS.size} default relays")
                println("   Total selected: ${combined.size} relays")
                combined
            } else {
                println("📋 NostrRelay: Using only nearest relays (no defaults)")
                nearest.toSet()
            }

            if (selected.isEmpty()) {
                println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                println("❌ NostrRelay: ERROR - No relays selected for geohash=$geohash")
                println("   Candidates found: ${nearest.size}")
                println("   Defaults included: $includeDefaults")
                println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                return
            }

            geohashToRelays[geohash] = selected
            println("✅ NostrRelay: Geohash $geohash mapped to ${selected.size} relays")
            selected.forEachIndexed { index, url ->
                println("   ${index + 1}. $url")
            }
            println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

            ensureConnectionsFor(selected)
        } catch (e: Exception) {
            println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            println("❌ NostrRelay: ERROR - Failed to ensure relays for $geohash")
            println("   Error: ${e.message}")
            println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            e.printStackTrace()
        }
    }

    fun closestRelaysForGeohash(geohash: String, nRelays: Int): List<String> {
        val snapshot = relayCache.getAllValues() //synchronized(relaysLock) { relays.toList() }
        if (snapshot.isEmpty()) return emptyList()
        val center = try {
            val c = GeohashUtils.decodeToCenter(geohash)
            c
        } catch (e: Exception) {
            // Log.e(TAG, "Failed to decode geohash '$geohash': ${e.message}")
            return emptyList()
        }

        val (lat, lon) = center
        return snapshot
            .asSequence()
            .sortedBy { haversineDistance(lat, lon, it.latitude, it.longitude) }
            .take(nRelays.coerceAtLeast(0))
            .map { it.url }
            .toList()
    }

    private fun ensureConnectionsFor(relayUrls: Set<String>) {
        // Ensure relays are tracked for UI/status
        relayUrls.forEach { url ->
            if (relaysList.none { it.url == url }) {
                relaysList.add(Relay(url))
            }
        }

        scope.launch {
            relayUrls.forEach { relayUrl ->
                launch {
                    if (!wsClient.isConnected(relayUrl)) {
                        connectToRelay(relayUrl)
                    }
                }
            }
        }
    }

    private suspend fun connectToRelay(relayUrl: String) {
        // ADDED: Idempotency check - don't reconnect if already connected or connecting
        if (wsClient.isConnected(relayUrl) || wsClient.isConnecting(relayUrl)) {
            return
        }

        println("NostrRelay: Connecting to $relayUrl...")
        RelayLogFormatter.connectAttempt(relayUrl)?.let { relayLogSink?.onLogLine(it) }

        try {
            val listener = object : NostrWebSocketListener {
                override fun onOpen(relayUrl: String) {
                    handleRelayOpen(relayUrl)
                }

                override fun onMessage(relayUrl: String, text: String) {
                    handleMessage(text, relayUrl)
                }

                override fun onClosing(relayUrl: String, code: Int, reason: String) {
                    // Relay is closing
                }

                override fun onClosed(relayUrl: String, code: Int, reason: String) {
                    val error = Exception("WebSocket closed: $code $reason")
                    handleDisconnection(relayUrl, error)
                }

                override fun onFailure(relayUrl: String, t: Throwable) {
                    handleDisconnection(relayUrl, t)
                }
            }

            wsClient.connect(
                relayUrl = relayUrl,
                listener = listener,
                maxReconnectAttempts = 10,
                initialBackoffMs = 1000L,
                maxBackoffMs = 60000L
            )
        } catch (e: Exception) {
            handleDisconnection(relayUrl, e)
        }
    }

    private fun handleRelayOpen(relayUrl: String) {
        println("NostrRelay: ✓ Connected to $relayUrl")
        RelayLogFormatter.connected(relayUrl)?.let { relayLogSink?.onLogLine(it) }
        updateRelayStatus(relayUrl, true)

        // Restore all active subscriptions for this relay
        restoreSubscriptionsForRelay(relayUrl)

        // Process any queued messages for this relay
        // Take a snapshot to avoid iterator modification from async coroutines
        val messagesToProcess = synchronized(messageQueueLock) {
            messageQueue.toList()
        }

        messagesToProcess.forEach { queuedMsg ->
            if (relayUrl in queuedMsg.targetRelays && relayUrl !in queuedMsg.deliveredTo) {
                scope.launch {
                    sendEventToRelay(queuedMsg.event, relayUrl)
                    synchronized(messageQueueLock) {
                        queuedMsg.deliveredTo.add(relayUrl)
                        // Only remove if delivered to ALL target relays
                        if (queuedMsg.deliveredTo.containsAll(queuedMsg.targetRelays)) {
                            messageQueue.remove(queuedMsg)
                            println("NostrRelay: ✅ Message ${queuedMsg.event.id.take(16)}... delivered to all ${queuedMsg.targetRelays.size} relays")
                        }
                    }
                }
            }
        }
    }

    fun sendEvent(event: NostrEvent, relayUrls: List<String>? = null) {
        val targetRelays = (relayUrls ?: relaysList.map { it.url }).toSet()

        // Add to queue for reliability
        synchronized(messageQueueLock) {
            messageQueue.add(QueuedMessage(event, targetRelays))
        }

        // Attempt immediate send
        scope.launch {
            targetRelays.forEach { relayUrl ->
                if (wsClient.isConnected(relayUrl)) {
                    sendEventToRelay(event, relayUrl)
                    // Mark as delivered
                    synchronized(messageQueueLock) {
                        messageQueue.find { it.event.id == event.id }?.deliveredTo?.add(relayUrl)
                    }
                }
            }
        }
    }

    fun sendEventToGeohash(event: NostrEvent, geohash: String, includeDefaults: Boolean = false, nRelays: Int = 5) {
        ensureGeohashRelaysConnected(geohash, nRelays, includeDefaults)
        val relayUrls = getRelaysForGeohash(geohash)
        if (relayUrls.isEmpty()) {
            // Log.w(TAG, "No target relays to send event for geohash=$geohash; falling back to defaults")
            sendEvent(event, DEFAULT_RELAYS)
            return
        }
        // Log.v(TAG, "📤 Sending event kind=${event.kind} to ${relayUrls.size} relays for geohash=$geohash")
        sendEvent(event, relayUrls)
    }

    fun getRelaysForGeohash(geohash: String): List<String> {
        return geohashToRelays[geohash]?.toList() ?: emptyList()
    }

    fun registerPendingGiftWrap(id: String) {
        pendingGiftWrapIDs.add(id)
    }

    /**
     * Subscribe to events matching the given filter.
     * @param subscriptionId Unique identifier for this subscription
     * @param filter The filter criteria for events
     * @param handler Callback invoked for each matching event
     * @param targetRelayUrls Optional specific relay URLs (null = all connected relays)
     * @param originGeohash Optional geohash for logging/grouping
     * @return The subscription ID
     */
    fun subscribe(
        subscriptionId: String,
        filter: NostrFilter,
        handler: (NostrEvent) -> Unit,
        targetRelayUrls: Set<String>? = null,
        originGeohash: String? = null
    ): String {
        println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        println("🔔 NostrRelay.subscribe")
        println("   Subscription ID: $subscriptionId")
        println("   Filter kinds: ${filter.kinds}")
        println("   Filter tags: ${filter.tagFilters}")
        println("   Origin geohash: ${originGeohash ?: "none"}")
        println("   Target relays: ${targetRelayUrls?.size ?: "all connected"}")
        targetRelayUrls?.forEachIndexed { index, url ->
            println("     ${index + 1}. $url")
        }
        println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

        // Create subscription info
        val subInfo = SubscriptionInfo(
            id = subscriptionId,
            filter = filter,
            handler = handler,
            targetRelayUrls = targetRelayUrls,
            originGeohash = originGeohash
        )

        // Store subscription
        activeSubscriptions[subscriptionId] = subInfo

        // Add handler to list (supporting multiple handlers per subscription)
        val handlers = messageHandlers.getOrPut(subscriptionId) { mutableListOf() }
        handlers.add(handler)

        // Send subscription requests to relays
        scope.launch {
            val relaysToSubscribe = if (targetRelayUrls != null) {
                targetRelayUrls.filter { wsClient.isConnected(it) }
            } else {
                relaysList.filter { it.isConnected }.map { it.url }
            }

            println("📤 NostrRelay: Sending subscription to ${relaysToSubscribe.size} connected relays")
            relaysToSubscribe.forEachIndexed { index, url ->
                println("   ${index + 1}. $url")
            }

            relaysToSubscribe.forEach { relayUrl ->
                sendSubscriptionRequest(subscriptionId, filter, relayUrl)
            }

            println("✅ NostrRelay: Subscription $subscriptionId sent to ${relaysToSubscribe.size} relays")
        }

        return subscriptionId
    }

    /**
     * Unsubscribe from events.
     * @param subscriptionId The subscription ID to remove
     */
    fun unsubscribe(subscriptionId: String) {
        activeSubscriptions.remove(subscriptionId)
        messageHandlers.remove(subscriptionId)

        // Send CLOSE message to relays
        scope.launch {
            val relaysToClose = relaysList.filter { it.isConnected }.map { it.url }
            relaysToClose.forEach { relayUrl ->
                sendCloseRequest(subscriptionId, relayUrl)
            }
        }
    }

    private suspend fun sendCloseRequest(subscriptionId: String, relayUrl: String) {
        try {
            val jsonArray = buildJsonArray {
                add(json.encodeToJsonElement("CLOSE"))
                add(json.encodeToJsonElement(subscriptionId))
            }
            val message = jsonArray.toString()
            wsClient.send(relayUrl, message)
        } catch (e: Exception) {
            // Failed to send close request
        }
    }

    private suspend fun sendEventToRelay(event: NostrEvent, relayUrl: String) {
        try {
            val jsonArray = buildJsonArray {
                add(json.encodeToJsonElement("EVENT"))
                add(json.encodeToJsonElement(event))
            }
            val message = jsonArray.toString()

            println("NostrRelay: 📤 EVENT -> $relayUrl kind=${event.kind} id=${event.id.take(16)}...")
            wsClient.send(relayUrl, message)

            // Update relay stats
            val relay = relaysList.find { it.url == relayUrl }
            relay?.messagesSent = (relay.messagesSent) + 1
            updateRelaysList()
        } catch (e: Exception) {
            // If relay is not connected, queue the message
            synchronized(messageQueueLock) {
                messageQueue.add(QueuedMessage(event, setOf(relayUrl)))
            }
        }
    }

    private fun handleMessage(message: String, relayUrl: String) {
        try {
            val response = try {
                json.decodeFromString<NostrResponse>(message)
            } catch (e: Exception) {
                return
            }

            when (response) {
                is NostrResponse.Event -> {
                    // Diagnostic logging - detailed event info for geohash events
                    if (response.event.kind == 20000) {
                        println("━━━ NOSTR RELAY RECEIVED EVENT ━━━")
                        println("Relay: $relayUrl")
                        println("Event ID: ${response.event.id}")
                        println("Event kind: ${response.event.kind}")
                        println("Sender pubkey: ${response.event.pubkey.take(16)}...")
                        println("Full pubkey: ${response.event.pubkey}")
                        println("Subscription: ${response.subscriptionId}")
                        val geohashTag = response.event.tags.find { it.firstOrNull() == "g" }?.getOrNull(1)
                        println("Geohash event - tag: $geohashTag")
                        println("Content preview: ${response.event.content.take(50)}${if (response.event.content.length > 50) "..." else ""}")
                        println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                    }

                    val relay = relaysList.find { it.url == relayUrl }
                    relay?.messagesReceived = (relay.messagesReceived) + 1
                    updateRelaysList()

                    println("NostrRelay: 📥 Received event kind=${response.event.kind} from $relayUrl, subId=${response.subscriptionId}")

                    activeSubscriptions[response.subscriptionId]?.let { subInfo ->
                        val matches = try {
                            subInfo.filter.matches(response.event)
                        } catch (e: Exception) {
                            true
                        }
                        if (!matches) {
                            // Do NOT call deduplicator here to allow the correct subscription to process it later
                            return
                        }
                    }

                    val wasProcessed = eventDeduplicator.processEvent(response.event) { event ->
                        if (event.kind != NostrKind.GIFT_WRAP) {
                            println("NostrRelay: Processing new event id=${event.id.take(16)}... kind=${event.kind}")
                        }

                        val matchingSubscriptions = activeSubscriptions.filter { (subId, subInfo) ->
                            try {
                                subInfo.filter.matches(event)
                            } catch (e: Exception) {
                                false
                            }
                        }

                        println("NostrRelay: Event matches ${matchingSubscriptions.size} subscription(s)")

                        // Invoke handlers for ALL matching subscriptions
                        matchingSubscriptions.forEach { (subId, subInfo) ->
                            val handlers = messageHandlers[subId] ?: emptyList()
                            if (handlers.isNotEmpty()) {
                                println("NostrRelay: Invoking ${handlers.size} handler(s) for subscription $subId")
                                handlers.forEach { handler ->
                                    scope.launch(Dispatchers.Main) {
                                        handler(event)
                                    }
                                }
                            }
                        }
                    }
                }

                is NostrResponse.EndOfStoredEvents -> {

                }

                is NostrResponse.Ok -> {

                }

                is NostrResponse.Notice -> {
                    println("NostrRelay: ⚠️ Notice from $relayUrl: ${response.message}")
                }

                is NostrResponse.Unknown -> {
                    // Unknown message type
                }
            }
        } catch (e: Exception) {
            // Failed to parse message
        }
    }

    private fun handleDisconnection(relayUrl: String, error: Throwable) {
        println("NostrRelay: ❌ Disconnection from $relayUrl")
        println("NostrRelay: Error type: ${error::class.simpleName}")
        println("NostrRelay: Error message: ${error.message}")
        error.printStackTrace()
        RelayLogFormatter.disconnected(relayUrl)?.let { relayLogSink?.onLogLine(it) }

        updateRelayStatus(relayUrl, false, error)

        // Check if this is a DNS error
        val errorMessage = error.message?.lowercase() ?: ""

        // Check for WebSocket plugin/configuration errors
        if (errorMessage.contains("websocket") ||
            errorMessage.contains("plugin") ||
            errorMessage.contains("not installed") ||
            errorMessage.contains("not configured")
        ) {
            println("NostrRelay: ⚠️ WebSocket plugin error - check HttpClient configuration")
            return  // Don't retry configuration errors
        }

        if (errorMessage.contains("hostname could not be found") ||
            errorMessage.contains("dns") ||
            errorMessage.contains("unable to resolve host")
        ) {
            println("NostrRelay: ⚠️ DNS resolution failed - relay may be offline")
            // Don't retry on DNS errors
            return
        }

        // Exponential backoff is handled by NostrWebSocketClient
    }

    private fun restoreSubscriptionsForRelay(relayUrl: String) {
        // Restore all active subscriptions for this relay
        activeSubscriptions.getAll().values.forEach { subInfo ->
            val targetRelays = subInfo.targetRelayUrls
            if (targetRelays == null || relayUrl in targetRelays) {
                scope.launch {
                    sendSubscriptionRequest(subInfo.id, subInfo.filter, relayUrl)
                }
            }
        }
    }

    private suspend fun sendSubscriptionRequest(subscriptionId: String, filter: NostrFilter, relayUrl: String) {
        try {
            val jsonArray = buildJsonArray {
                add(json.encodeToJsonElement("REQ"))
                add(json.encodeToJsonElement(subscriptionId))
                add(json.encodeToJsonElement(filter))
            }
            val message = jsonArray.toString()
            wsClient.send(relayUrl, message)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun updateRelayStatus(relayUrl: String, isConnected: Boolean, error: Throwable? = null) {
        val relay = relaysList.find { it.url == relayUrl } ?: return
        relay.isConnected = isConnected
        relay.lastError = error
        if (isConnected) {
            relay.lastConnectedAt = Clock.System.now().toEpochMilliseconds()
            relay.reconnectAttempts = 0
        } else {
            relay.lastDisconnectedAt = Clock.System.now().toEpochMilliseconds()
        }
        updateRelaysList()
    }

    private fun updateRelaysList() {
        // Notify listeners of status change (implement as needed)
    }

    /**
     * Relay status information
     */
    data class Relay(
        val url: String,
        var isConnected: Boolean = false,
        var lastError: Throwable? = null,
        var lastConnectedAt: Long? = null,
        var messagesSent: Int = 0,
        var messagesReceived: Int = 0,
        var reconnectAttempts: Int = 0,
        var lastDisconnectedAt: Long? = null,
        var nextReconnectTime: Long? = null
    )

    /**
     * Information about an active subscription that needs to be maintained across reconnections
     */
    data class SubscriptionInfo(
        val id: String,
        val filter: NostrFilter,
        val handler: (NostrEvent) -> Unit,
        val targetRelayUrls: Set<String>? = null, // null means all relays
        val createdAt: Long = Clock.System.now().toEpochMilliseconds(),
        val originGeohash: String? = null // used for logging and grouping
    )

    // ========== NIP-28 Named Channel Methods ==========

    /**
     * Subscribe to channel messages (kind 42) for a specific named channel.
     * Messages reference the channel creation event (kind 40) via the "e" tag.
     *
     * @param channelEventId The ID of the kind 40 channel creation event
     * @param handler Callback invoked for each channel message
     * @param targetRelayUrls Optional specific relay URLs (null = all connected relays)
     * @return The subscription ID
     */
    fun subscribeToChannelMessages(
        channelEventId: String,
        handler: (NostrEvent) -> Unit,
        targetRelayUrls: Set<String>? = null
    ): String {
        // use shortened eventId to keep subscription ID under 64 chars (relay limit)
        val shortEventId = channelEventId.take(16)
        val subscriptionId = "chan_$shortEventId"

        val filter = NostrFilter(
            kinds = listOf(NostrKind.CHANNEL_MESSAGE),
            tagFilters = mapOf("e" to listOf(channelEventId))
        )

        return subscribe(
            subscriptionId = subscriptionId,
            filter = filter,
            handler = handler,
            targetRelayUrls = targetRelayUrls
        )
    }

    /**
     * Subscribe to channel creation events (kind 40) to discover named channels.
     *
     * @param handler Callback invoked for each channel creation event
     * @param channelName Optional filter by channel name (searches in content)
     * @param targetRelayUrls Optional specific relay URLs (null = all connected relays)
     * @return The subscription ID
     */
    fun subscribeToChannelCreations(
        handler: (NostrEvent) -> Unit,
        channelName: String? = null,
        targetRelayUrls: Set<String>? = null
    ): String {
        val subscriptionId = if (channelName != null) {
            "channel_create_${channelName.hashCode()}"
        } else {
            "channel_create_all"
        }

        val filter = NostrFilter(
            kinds = listOf(NostrKind.CHANNEL_CREATE)
        )

        println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        println("🔔 NostrRelay.subscribeToChannelCreations")
        println("   Channel name filter: ${channelName ?: "all channels"}")
        println("   Subscription ID: $subscriptionId")
        println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

        // Wrap handler to filter by channel name if specified
        val filteredHandler: (NostrEvent) -> Unit = if (channelName != null) {
            { event ->
                val info = NostrEvent.parseChannelInfo(event)
                if (info?.name == channelName) {
                    handler(event)
                }
            }
        } else {
            handler
        }

        return subscribe(
            subscriptionId = subscriptionId,
            filter = filter,
            handler = filteredHandler,
            targetRelayUrls = targetRelayUrls
        )
    }

    /**
     * Send a channel creation event (kind 40) to relays.
     * @param event The channel creation event
     * @param relayUrls Optional specific relay URLs (null = default relays)
     */
    fun sendChannelCreation(event: NostrEvent, relayUrls: List<String>? = null) {
        require(event.kind == NostrKind.CHANNEL_CREATE) {
            "Expected kind 40 (CHANNEL_CREATE), got ${event.kind}"
        }
        println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        println("📤 NostrRelay.sendChannelCreation")
        println("   Event ID: ${event.id}")
        println("   Content: ${event.content.take(50)}...")
        println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        sendEvent(event, relayUrls ?: DEFAULT_RELAYS)
    }

    /**
     * Send a channel message event (kind 42) to relays.
     * @param event The channel message event
     * @param relayUrls Optional specific relay URLs (null = default relays)
     */
    fun sendChannelMessage(event: NostrEvent, relayUrls: List<String>? = null) {
        require(event.kind == NostrKind.CHANNEL_MESSAGE) {
            "Expected kind 42 (CHANNEL_MESSAGE), got ${event.kind}"
        }
        println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        println("📤 NostrRelay.sendChannelMessage")
        println("   Event ID: ${event.id}")
        println("   Content length: ${event.content.length}")
        println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        sendEvent(event, relayUrls ?: DEFAULT_RELAYS)
    }

    /**
     * Unsubscribe from channel messages for a specific channel.
     * @param channelEventId The ID of the kind 40 channel creation event
     */
    fun unsubscribeFromChannelMessages(channelEventId: String) {
        val shortEventId = channelEventId.take(16)
        val subscriptionId = "chan_$shortEventId"
        unsubscribe(subscriptionId)
    }

    fun unsubscribeFromChannelCreations(channelName: String? = null) {
        val subscriptionId = if (channelName != null) {
            "channel_create_${channelName.hashCode()}"
        } else {
            "channel_create_all"
        }
        unsubscribe(subscriptionId)
    }
}
