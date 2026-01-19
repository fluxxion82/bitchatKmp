package com.bitchat.client.model

data class RetryConfig(
    val maxAttempts: Int = 3,
    val initialDelay: Long = 1000, // 1 second
    val maxDelay: Long = 5000,     // 5 seconds
    val factor: Double = 2.0       // exponential backoff factor
)
