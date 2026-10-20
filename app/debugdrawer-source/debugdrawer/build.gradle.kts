// The SloopWorks debug drawer, compiled from a checkout's sources with this build's toolchain (see
// settings.gradle.kts for why not includeBuild). Android only: the iOS framework is produced by :composeApp,
// which cannot take a debug-only dependency, so iOS has no drawer yet.
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

val drawerRoot = file(providers.gradleProperty("debugDrawerPath").orNull ?: "${rootDir}/../../debugdrawer")

kotlin {
    android {
        namespace = "com.sloopworks.debugdrawer"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(drawerRoot.resolve("debugdrawer/src/commonMain/kotlin"))
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)
                implementation(libs.kotlinx.coroutines.core)
                implementation("org.jetbrains.kotlinx:atomicfu:0.27.0")
            }
        }
        androidMain {
            kotlin.srcDir(drawerRoot.resolve("debugdrawer/src/androidMain/kotlin"))
        }
    }
}
