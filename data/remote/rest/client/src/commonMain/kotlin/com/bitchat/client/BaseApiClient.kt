package com.bitchat.client

import com.bitchat.client.model.ApiException
import com.bitchat.client.model.RetryConfig
import com.bitchat.domain.base.model.Outcome
import com.bitchat.domain.base.model.failure.Failure
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.pow

class BaseApiClient(
    val client: HttpClient,
    private val retryConfig: RetryConfig = RetryConfig()
) {
    suspend inline fun <reified T> get(
        path: String,
        crossinline builder: HttpRequestBuilder.() -> Unit = {}
    ): Outcome<T> {
        return executeWithRetry {
            val response = client.get(path, builder)
            handleResponse(response)
        }
    }

    suspend inline fun <reified T> post(
        path: String,
        crossinline builder: HttpRequestBuilder.() -> Unit = {}
    ): Outcome<T> {
        return executeWithRetry {
            val response = client.post(path, builder)
            handleResponse(response)
        }
    }

    suspend inline fun <reified T> put(
        path: String,
        crossinline builder: HttpRequestBuilder.() -> Unit = {}
    ): Outcome<T> {
        return executeWithRetry {
            val response = client.put(path, builder)
            handleResponse(response)
        }
    }

    suspend inline fun <reified T> delete(
        path: String,
        crossinline builder: HttpRequestBuilder.() -> Unit = {}
    ): Outcome<T> {
        return executeWithRetry {
            val response = client.delete(path, builder)
            handleResponse(response)
        }
    }

    suspend inline fun <reified T> submitFormWithBinaryData(
        path: String,
        noinline builder: FormBuilder.() -> Unit
    ): Outcome<T> {
        return executeWithRetry {
            val response = client.submitFormWithBinaryData(
                url = path,
                formData = formData(builder)
            )
            handleResponse(response)
        }
    }

    suspend inline fun <reified T> handleResponse(response: HttpResponse): T {
        return when (response.status) {
            HttpStatusCode.OK, HttpStatusCode.Created -> response.body<T>()
            HttpStatusCode.Unauthorized -> throw ApiException.TokenExpired()
            HttpStatusCode.TooManyRequests -> {
                val retryAfter = response.headers["Retry-After"]?.toLongOrNull()
                throw ApiException.RateLimited(retryAfter)
            }

            else -> throw ApiException.ServerError(response.status.value, response.body())
        }
    }

    suspend fun <T> executeWithRetry(
        apiCall: suspend () -> T
    ): Outcome<T> = withContext(Dispatchers.Default) {
        try {
            var attempts = 0
            var lastException: Exception? = null

            while (attempts < retryConfig.maxAttempts) {
                try {
                    val result = apiCall()
                    return@withContext Outcome.Success(result)
                } catch (e: HttpRequestTimeoutException) {
                    e.printStackTrace()
                    lastException = e
                    // Handle timeout exception with retry
                    val delay = calculateBackoffDelay(attempts)
                    delay(delay)
                    attempts++
                    continue
                } catch (e: ApiException) {
                    e.printStackTrace()
                    lastException = e
                    when (e) {
                        is ApiException.RateLimited -> {
                            val delay = calculateBackoffDelay(attempts, e.retryAfter)
                            delay(delay)
                            attempts++
                            continue
                        }

                        else -> {
                            if (e is ApiException.ServerError && e.code >= 500) {
                                val delay = calculateBackoffDelay(attempts)
                                delay(delay)
                                attempts++
                                continue
                            }
                            break
                        }
                    }
                }
            }

            if (lastException is ApiException) {
                handleApiException(lastException)
            } else if (lastException is HttpRequestTimeoutException) {
                Outcome.Error("Request timeout", Failure.NetworkError("Request timeout after ${retryConfig.maxAttempts} attempts"))
            } else {
                Outcome.Error("Unknown error", Failure.UnknownError)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Outcome.Error("Network error", Failure.NetworkError(e.message.orEmpty()))
        }
    }

    private fun calculateBackoffDelay(attempt: Int, retryAfter: Long? = null): Long {
        if (retryAfter != null) return retryAfter

        val exponentialDelay = (retryConfig.initialDelay * retryConfig.factor.pow(attempt.toDouble())).toLong()
        return exponentialDelay.coerceAtMost(retryConfig.maxDelay)
    }

    private fun handleApiException(exception: ApiException?): Outcome<Nothing> = when (exception) {
        is ApiException.NetworkError -> Outcome.Error(
            "Network error",
            Failure.NetworkError(exception.message.orEmpty())
        )

        is ApiException.ServerError -> Outcome.Error(
            "Server error ${exception.code}",
            Failure.ServerError(exception.code, exception.body)
        )

        is ApiException.TokenExpired -> Outcome.Error(
            "Authentication failed",
            Failure.AuthenticationError(exception.message)
        )

        is ApiException.RateLimited -> Outcome.Error("Rate limited", Failure.RateLimitError)
        null -> Outcome.Error("Unknown error", Failure.UnknownError)
    }
}