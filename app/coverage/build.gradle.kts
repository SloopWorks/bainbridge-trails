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
    testLogging {
        showStandardStreams = xint
        events("skipped") // dataset-backed tests skip (JUnit assumption) when the gitignored data is absent
    }
    // Real-dataset tests read these by relative path; declare them so a cached result from a checkout with the data
    // is never reused by one without it (and vice versa). Missing directories are fine (empty file trees).
    inputs.files(
        fileTree("../../data/dist") { include("trails.json", "coverage_segments.json") },
        fileTree("../composeApp/src/commonMain/composeResources/files") {
            include("trails.json", "coverage_segments.json", "dataset.bin")
        },
    ).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("datasetFiles")
}
