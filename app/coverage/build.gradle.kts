plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    jvm()

    android {
        namespace = "com.bainbridgetrails.coverage"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

// LoadBench: -Pcoverage.benchXint runs jvmTest interpreted (rough proxy for a debuggable Android build)
tasks.named<Test>("jvmTest") {
    val xint = providers.gradleProperty("coverage.benchXint").isPresent
    if (xint) jvmArgs("-Xint")
    testLogging { showStandardStreams = xint }
}
