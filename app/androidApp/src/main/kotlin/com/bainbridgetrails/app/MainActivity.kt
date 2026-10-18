package com.bainbridgetrails.app

import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.bainbridgetrails.App
import com.bainbridgetrails.map.SpikeLaunch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Spike only, debuggable builds only (this activity is exported, so release must ignore extras):
        // `adb shell am start -n ... --es style DARK --es lat 47.655 --es lon -122.548 --es zoom 15`
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val extras = if (debuggable) intent.extras else null
        val options = extras?.keySet()?.associateWith { extras.getString(it).orEmpty() }.orEmpty()
        setContent { App(SpikeLaunch.parse(options)) }
    }
}
