package com.bainbridgetrails.log

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

/**
 * App-wide log facade. Defaults to the platform log; debug builds tee into the debug drawer's log viewer by
 * replacing [sink] at startup. Never log coordinates or track contents (raw tracks are private).
 */
object AppLog {
    fun interface Sink {
        fun log(level: LogLevel, tag: String, message: String)
    }

    var sink: Sink = Sink { level, tag, message -> platformLog(level, tag, message) }

    fun d(tag: String, message: String) = sink.log(LogLevel.DEBUG, tag, message)
    fun i(tag: String, message: String) = sink.log(LogLevel.INFO, tag, message)
    fun w(tag: String, message: String) = sink.log(LogLevel.WARN, tag, message)
    fun e(tag: String, message: String) = sink.log(LogLevel.ERROR, tag, message)
}

internal expect fun platformLog(level: LogLevel, tag: String, message: String)
