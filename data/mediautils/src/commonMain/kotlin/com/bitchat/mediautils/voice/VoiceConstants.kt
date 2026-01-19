package com.bitchat.mediautils.voice

object VoiceConstants {
    /** Maximum recording duration in milliseconds (10 seconds) */
    const val MAX_DURATION_MS = 10_000L

    /** Minimum recording duration in milliseconds - recordings shorter than this are cancelled */
    const val MIN_DURATION_MS = 1_000L

    /** Amplitude polling interval in milliseconds */
    const val AMPLITUDE_POLL_MS = 80L

    /** Extra buffer time after touch release before finalizing recording */
    const val BUFFER_AFTER_RELEASE_MS = 500L

    /** Audio sample rate in Hz */
    const val SAMPLE_RATE = 16000

    /** Audio bit rate in bits per second */
    const val BIT_RATE = 20_000

    /** Number of audio channels (mono) */
    const val CHANNELS = 1
}
