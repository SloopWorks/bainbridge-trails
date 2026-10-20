package com.bainbridgetrails.data

import com.bainbridgetrails.record.PlatformContext

/** Private app data dir for small app files (settings). Backed up (Android filesDir; iOS Application Support/app). */
expect fun appDataDir(context: PlatformContext): String
