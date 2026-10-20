package com.bainbridgetrails.log

import platform.Foundation.NSLog

internal actual fun platformLog(level: LogLevel, tag: String, message: String) {
    // No variadic arguments: passing a Kotlin String through NSLog's C varargs segfaults on arm64 (seen in
    // iosSimulatorArm64Test). The text is the format, with '%' escaped.
    NSLog("${level.name.first()}/$tag: $message".replace("%", "%%"))
}
