package com.bitchat.repo.tor

import com.bitchat.domain.tor.TorCapability

/** Capability derived from the HTTP engine and the platform Tor runtime. */
internal class PlatformTorCapability(
    private val engineSupportsTorProxy: Boolean,
    private val isTorAvailable: () -> Boolean?,
) : TorCapability {
    override fun canRouteTraffic(): Boolean = engineSupportsTorProxy && isTorAvailable() == true
}
