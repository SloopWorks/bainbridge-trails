package com.bainbridgetrails.data

/** A value that loads asynchronously and may fail (the UI shows a retry). */
sealed interface Loadable<out T> {
    data object Loading : Loadable<Nothing>
    data class Ready<T>(val value: T) : Loadable<T>
    data class Failed(val message: String) : Loadable<Nothing>
}

val <T> Loadable<T>.valueOrNull: T? get() = (this as? Loadable.Ready)?.value
