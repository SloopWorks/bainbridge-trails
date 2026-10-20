// No-op stand-in for the SloopWorks debug drawer, used only when no drawer checkout is available (see
// settings.gradle.kts). Same package and the subset of the public API this app uses, so app code compiles
// unchanged against the real drawer (debug), its noop twin (release) or this shim.
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    android {
        namespace = "com.sloopworks.debugdrawer.shim"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
        }
    }
}
