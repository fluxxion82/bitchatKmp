package com.bitchat.nostr.util

import com.bitchat.domain.chat.model.nextSendTime
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.time.Clock

/**
 * The time written inside each private message sent over Nostr, in whole seconds. The other side
 * sorts a conversation by it and relays hand messages back in any order, so messages handed over
 * in a row are dated one second apart in that order (see [nextSendTime]), never more than
 * [MAX_SECONDS_AHEAD] ahead of the clock: upstream refuses a message dated fifteen minutes ahead.
 */
internal class RumorClock(private val nowSeconds: () -> Long = { Clock.System.now().epochSeconds }) {
    private val lock = SynchronizedObject()
    private var last = 0L

    fun next(): Int = synchronized(lock) {
        last = nextSendTime(last, nowSeconds(), MAX_SECONDS_AHEAD)
        last.toInt()
    }

    companion object {
        const val MAX_SECONDS_AHEAD = 300L
    }
}
