package com.bainbridgetrails.log

import android.util.Log

internal actual fun platformLog(level: LogLevel, tag: String, message: String) {
    // Host (JVM) unit tests run against the stub android.jar: fall back to stdout there.
    try {
        when (level) {
            LogLevel.DEBUG -> Log.d(tag, message)
            LogLevel.INFO -> Log.i(tag, message)
            LogLevel.WARN -> Log.w(tag, message)
            LogLevel.ERROR -> Log.e(tag, message)
        }
    } catch (_: Throwable) { // "Stub!" RuntimeException or no android.jar at all
        println("$level/$tag: $message")
    }
}
