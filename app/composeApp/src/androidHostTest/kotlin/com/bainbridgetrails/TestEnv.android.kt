package com.bainbridgetrails

internal actual fun testEnv(name: String): String? = System.getenv(name)
