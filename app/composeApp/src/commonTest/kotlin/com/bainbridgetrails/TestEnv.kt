package com.bainbridgetrails

/** The test process's environment variable [name], or null (RoadsGuardTest's CI check, privacy review L2-2 P3-1). */
internal expect fun testEnv(name: String): String?
