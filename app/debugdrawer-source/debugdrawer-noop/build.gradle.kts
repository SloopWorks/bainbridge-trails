// The drawer's API-identical no-op twin (release builds), compiled from the same checkout as :debugdrawer.
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

val drawerRoot = file(providers.gradleProperty("debugDrawerPath").orNull ?: "${rootDir}/../../debugdrawer")

kotlin {
    android {
        namespace = "com.sloopworks.debugdrawer.noop"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(drawerRoot.resolve("debugdrawer-noop/src/commonMain/kotlin"))
            dependencies {
                implementation(compose.runtime)
                implementation(compose.ui)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
    }
}
