package com.bitchat.repo.utils

import kotlinx.atomicfu.atomic

/**
 * How much disk the files received in one run of the app may take. Mesh senders are not authenticated
 * and each received file is kept, so without this a peer in range decides how full the disk gets.
 *
 * One total for the app, not one per peer: a peer id is whatever the sender says it is. Nothing is ever
 * deleted to make room, so a file a message shows stays; once the total is reached further files are
 * refused until the app is started again. A charge is never given back, not even when the save fails:
 * a failed save can leave a directory or part of a file behind.
 */
class ReceivedFileBudget(private val limitBytes: Long = DEFAULT_LIMIT_BYTES) {
    private val chargedBytes = atomic(0L)

    /**
     * Charges one received file of [fileBytes] bytes, or returns false and charges nothing when that
     * would pass the limit. A file is charged at least [MINIMUM_CHARGE_BYTES]: even a one-byte file
     * costs a directory and a block on disk.
     */
    fun reserve(fileBytes: Int): Boolean {
        val charge = maxOf(fileBytes, MINIMUM_CHARGE_BYTES).toLong()
        while (true) {
            val current = chargedBytes.value
            if (charge > limitBytes - current) return false
            if (chargedBytes.compareAndSet(current, current + charge)) return true
        }
    }

    companion object {
        const val DEFAULT_LIMIT_BYTES: Long = 100L * 1024 * 1024
        const val MINIMUM_CHARGE_BYTES: Int = 16 * 1024
    }
}
