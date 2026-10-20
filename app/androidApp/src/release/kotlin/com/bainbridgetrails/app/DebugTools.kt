package com.bainbridgetrails.app

import android.app.Activity
import android.content.Context

/** Release: no debug drawer, no analytics (spec: none by default), production backend (ApiConfig default). */
object DebugTools {
    fun install(context: Context) = Unit
    fun onActivityCreated(activity: Activity) = Unit
}
