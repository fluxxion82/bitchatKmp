package com.bitchat.client.model

sealed class ApiException : Exception() {
    data class NetworkError(override val cause: Throwable? = null) : ApiException()
    data class ServerError(val code: Int, val body: String?) : ApiException()
    data class TokenExpired(override val message: String = "Token expired") : ApiException()
    data class RateLimited(val retryAfter: Long? = null) : ApiException()
}
