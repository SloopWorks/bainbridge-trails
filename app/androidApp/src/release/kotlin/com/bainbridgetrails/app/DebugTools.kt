package com.bainbridgetrails.app

import android.content.Context

/** Release: no debug drawer, no analytics (spec: none by default), production backend (ApiConfig default). */
object DebugTools {
    fun install(context: Context) = Unit
}
