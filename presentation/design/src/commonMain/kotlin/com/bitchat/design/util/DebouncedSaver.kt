package com.bitchat.design.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class DebouncedSaver(
    private val scope: CoroutineScope,
    private val debounceMillis: Long = 500,
    private val onSave: suspend (String) -> Unit
) {
    private var pendingJob: Job? = null
    private var lastSaved: String? = null

    private suspend fun persist(value: String) {
        onSave(value)
        lastSaved = value
    }

    fun submit(value: String) {
        pendingJob?.cancel()
        pendingJob = scope.launch {
            delay(debounceMillis)
            persist(value)
        }
    }

    fun flush(value: String) {
        pendingJob?.cancel()
        pendingJob = null
        if (lastSaved != value) {
            scope.launch { persist(value) }
        }
    }
}
