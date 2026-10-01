package com.bitchat.repo.tor

import com.bitchat.tor.TorManager

/** The Apple coordinator's sole adapter to the platform Tor manager. */
class TorManagerLifecycleManager(
    private val torManager: TorManager,
) : TorLifecycleManager {
    override suspend fun start() = torManager.start()
    override suspend fun stop() = torManager.stop()
}
