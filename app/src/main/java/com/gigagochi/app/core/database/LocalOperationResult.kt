package com.gigagochi.app.core.database

sealed interface LocalOperationResult<out T> {
    data class Success<T>(val value: T) : LocalOperationResult<T>
    data object Failure : LocalOperationResult<Nothing>
}
