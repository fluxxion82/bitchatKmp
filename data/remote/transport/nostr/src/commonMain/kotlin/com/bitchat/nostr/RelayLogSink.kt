package com.bitchat.nostr

fun interface RelayLogSink {
    fun onLogLine(line: String)
}
