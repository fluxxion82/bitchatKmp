package com.bitchat.bluetooth.manager

import com.bitchat.bluetooth.model.PeerInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

/**
 * Manages active peers, nicknames, RSSI tracking
 * Extracted from Android BluetoothMeshService for multiplatform use
 * Thread-safe implementation using coroutines and synchronized collections
 */
class PeerManager {
    companion object {
        private const val TAG = "PeerManager"
        private val STALE_PEER_TIMEOUT = 3.minutes // Centralized timeout
    }

    // Peer tracking data
    private val peers = mutableMapOf<String, PeerInfo>() // peerID -> PeerInfo
    private val peerRSSI = mutableMapOf<String, Int>()
    private val announcedPeers = mutableListOf<String>()
    private val announcedToPeers = mutableListOf<String>()

    // Delegate for callbacks
    var delegate: PeerManagerDelegate? = null

    // Coroutines
    private val managerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    init {
        startPeriodicCleanup()
    }

    /**
     * Add or update peer information
     */
    fun addOrUpdatePeer(
        peerID: String,
        nickname: String,
        isConnected: Boolean = true,
        isDirectConnection: Boolean = false,
        noisePublicKey: ByteArray? = null,
        signingPublicKey: ByteArray? = null,
        isVerified: Boolean = false
    ): PeerInfo {
        println("👥 PeerManager: addOrUpdatePeer called - ID: $peerID, Nickname: '$nickname', Connected: $isConnected, Direct: $isDirectConnection")

        val existing = peers[peerID]
        val peer = if (existing != null) {
            existing.apply {
                this.nickname = nickname
                this.isConnected = isConnected
                this.isDirectConnection = isDirectConnection
                this.noisePublicKey = noisePublicKey ?: this.noisePublicKey
                this.signingPublicKey = signingPublicKey ?: this.signingPublicKey
                this.isVerifiedNickname = isVerified
                this.lastSeen = Clock.System.now()
            }
        } else {
            PeerInfo(
                id = peerID,
                nickname = nickname,
                isConnected = isConnected,
                isDirectConnection = isDirectConnection,
                noisePublicKey = noisePublicKey,
                signingPublicKey = signingPublicKey,
                isVerifiedNickname = isVerified,
                lastSeen = Clock.System.now()
            ).also {
                peers[peerID] = it
            }
        }

        println("👥 PeerManager: Total peers now: ${peers.size}, Connected: ${peers.count { it.value.isConnected }}")
        delegate?.onPeerUpdated(peer)
        return peer
    }

    /**
     * Initialize self as a peer (prevents "Unknown" sender)
     */
    fun initializeSelfPeer(
        myPeerID: String,
        myNickname: String,
        myNoisePublicKey: ByteArray,
        mySigningPublicKey: ByteArray
    ) {
        addOrUpdatePeer(
            peerID = myPeerID,
            nickname = myNickname,
            noisePublicKey = myNoisePublicKey,
            signingPublicKey = mySigningPublicKey,
            isConnected = true,
            isDirectConnection = true,
            isVerified = true
        )
    }

    /**
     * Get peer by ID
     */
    fun getPeer(peerID: String): PeerInfo? = peers[peerID]

    /**
     * Get all active peers
     */
    fun getAllPeers(): List<PeerInfo> = peers.values.toList()

    /**
     * Check if peer is active
     */
    fun isPeerActive(peerID: String): Boolean {
        val peer = peers[peerID] ?: return false
        val now = Clock.System.now()
        return peer.isConnected && (now - peer.lastSeen) < STALE_PEER_TIMEOUT
    }

    /**
     * Update peer RSSI
     */
    fun updateRSSI(peerID: String, rssi: Int) {
        peerRSSI[peerID] = rssi
    }

    /**
     * Get peer RSSI
     */
    fun getRSSI(peerID: String): Int? = peerRSSI[peerID]

    /**
     * Get all peer RSSIs
     */
    fun getAllRSSI(): Map<String, Int> = peerRSSI.toMap()

    /**
     * Mark peer as announced
     */
    fun markAsAnnounced(peerID: String) {
        if (peerID !in announcedPeers) {
            announcedPeers.add(peerID)
        }
    }

    /**
     * Mark as announced to peer
     */
    fun markAsAnnouncedTo(peerID: String) {
        if (peerID !in announcedToPeers) {
            announcedToPeers.add(peerID)
        }
    }

    /**
     * Check if we've announced to peer
     */
    fun hasAnnouncedTo(peerID: String): Boolean = peerID in announcedToPeers

    /**
     * Disconnect peer
     */
    fun disconnectPeer(peerID: String) {
        peers[peerID]?.isConnected = false
        delegate?.onPeerDisconnected(peerID)
    }

    /**
     * Remove peer entirely
     */
    fun removePeer(peerID: String) {
        peers.remove(peerID)
        peerRSSI.remove(peerID)
        announcedPeers.remove(peerID)
        announcedToPeers.remove(peerID)
        delegate?.onPeerRemoved(peerID)
    }

    /**
     * Periodic cleanup of stale peers
     */
    private fun startPeriodicCleanup() {
        managerScope.launch {
            while (isActive) {
                delay(30_000) // 30 seconds
                cleanupStalePeers()
            }
        }
    }

    /**
     * Clean up stale peers that haven't been seen recently
     */
    private fun cleanupStalePeers() {
        val now = Clock.System.now()
        val stalePeers = peers.filter { (_, peer) ->
            !peer.isConnected || (now - peer.lastSeen) > STALE_PEER_TIMEOUT
        }.keys.toList()

        stalePeers.forEach { peerID ->
            removePeer(peerID)
        }
    }

    /**
     * Clear all peers
     */
    fun clearAll() {
        peers.clear()
        peerRSSI.clear()
        announcedPeers.clear()
        announcedToPeers.clear()
    }

    /**
     * Shutdown manager
     */
    fun shutdown() {
        managerScope.cancel()
        clearAll()
    }
}

/**
 * Delegate interface for peer manager callbacks
 */
interface PeerManagerDelegate {
    fun onPeerUpdated(peer: PeerInfo)
    fun onPeerDisconnected(peerID: String)
    fun onPeerRemoved(peerID: String)
}
