package com.bitchat.domain.base.model.failure

sealed class Failure {
    data class NetworkError(val message: String) : Failure()
    open class ServerError(open val code: Int, open val body: String?) : Failure()
    data class AuthenticationError(val message: String) : Failure()
    data object RateLimitError : Failure()
    data object UnknownError : Failure()

    abstract class FeatureFailure : Failure()
}
