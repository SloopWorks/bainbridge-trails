plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    compilerOptions {
        // PlatformContext (expect abstract class / actual typealias) — the Coil pattern.
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    android {
        namespace = "com.bainbridgetrails.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        androidResources {
            enable = true
        }
        withHostTest {}
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.components.resources)
            implementation(libs.maplibre.compose)
            implementation(libs.kotlinx.io.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(project(":tracking"))
        }
        androidMain.dependencies {
            implementation(libs.play.services.location)
            implementation(libs.androidx.activity.compose)
            // MapLibre Native render backend. OpenGL ES: widest device + emulator support.
            runtimeOnly(libs.maplibre.compose.runtime.opengl.android)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

compose.resources {
    packageOfResClass = "com.bainbridgetrails.resources"
}
