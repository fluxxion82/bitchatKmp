package com.bitchat.nostr.logging

import kotlin.time.Clock

const val NOSTR_DEBUG_TAG = "[NOSTR-DEBUG]"

fun logNostrDebug(component: String, message: String) {
    val timestamp = Clock.System.now()
    println("$NOSTR_DEBUG_TAG $timestamp [$component] $message")
}
