plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization) // type-safe navigation routes, settings.json
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
            // Already on the classpath via Compose; explicit because RecordScreen uses LifecycleResumeEffect.
            implementation(libs.androidx.lifecycle.runtime.compose)
            implementation(libs.androidx.navigation.compose)
            implementation(libs.ktor.client.core)
        }
        androidMain.dependencies {
            implementation(libs.play.services.location)
            implementation(libs.androidx.activity.compose)
            // MapLibre Native render backend. OpenGL ES: widest device + emulator support.
            runtimeOnly(libs.maplibre.compose.runtime.opengl.android)
            implementation(libs.ktor.client.okhttp)
            // Sign in with Google through Credential Manager (play-services-auth backs it below Android 14).
            implementation(libs.androidx.credentials)
            implementation(libs.androidx.credentials.play.services.auth)
            implementation(libs.googleid)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
    }
}

// Integration smoke against a local `wrangler dev` (skipped unless set):
// ./gradlew :composeApp:testAndroidHostTest -PbtApiUrl=http://localhost:8787 --tests '*BackendSmokeTest*'
tasks.withType<Test>().configureEach {
    providers.gradleProperty("btApiUrl").orNull?.let { systemProperty("bt.apiUrl", it) }
}

compose.resources {
    packageOfResClass = "com.bainbridgetrails.resources"
}
