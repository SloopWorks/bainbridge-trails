package com.bainbridgetrails.log

import platform.Foundation.NSLog

internal actual fun platformLog(level: LogLevel, tag: String, message: String) {
    NSLog("%@", "${level.name.first()}/$tag: $message")
}
