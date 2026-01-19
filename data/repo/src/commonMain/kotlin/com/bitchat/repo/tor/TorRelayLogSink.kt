package com.bitchat.repo.tor

import com.bitchat.nostr.RelayLogSink
import com.bitchat.repo.repositories.TorRepo

class TorRelayLogSink(
    private val torRepo: TorRepo,
) : RelayLogSink {
    override fun onLogLine(line: String) {
        torRepo.recordExternalLogLine(line)
    }
}
