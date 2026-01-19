package com.bitchat.domain.base.model

import com.bitchat.domain.base.model.failure.Failure
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

sealed class Outcome<out TResult : Any?> {
    data class Success<out TResult : Any?>(val value: TResult) : Outcome<TResult>()
    data class Error(val message: String, val cause: Failure) : Outcome<Nothing>()
}

@OptIn(ExperimentalContracts::class)
fun <TResult : Any> Outcome<TResult>.isSuccess(): Boolean {
    contract {
        returns(true) implies (this@isSuccess is Outcome.Success)
        returns(false) implies (this@isSuccess is Outcome.Error)
    }
    return this is Outcome.Success
}
