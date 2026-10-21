plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.kotlinSerialization)
}

// Pure Play logic (docs/play/engine.md PF-8, schedule.md §5.1): no Compose, no MapLibre, no :coverage, no :composeApp.
kotlin {
    jvm()

    android {
        namespace = "com.bainbridgetrails.play"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

// -Pplay.updateGolden=true rewrites the scheduler goldens (agenda-*.tsv, data/play/golden/date-rules-2027.json,
// docs/play/e2e-ids.env) instead of comparing; the PR diff is the review artifact.
tasks.named<Test>("jvmTest") {
    val updateGolden = providers.gradleProperty("play.updateGolden").orNull == "true"
    systemProperty("play.updateGolden", updateGolden.toString())
    if (updateGolden) outputs.upToDateWhen { false }
    testLogging {
        events("skipped") // dataset-backed tests skip (JUnit assumption) when the gitignored data is absent
    }
    // Tests read these by relative path; declare them so a cached result from a checkout with the data is never
    // reused by one without it (and vice versa). Missing files are fine (empty file trees).
    inputs.files(
        fileTree("../../data/dist") { include("trails.json", "coverage_segments.json", "trailheads.json") },
        fileTree("../../data/roads/dist") { include("roads.json", "coverage_segments.json") },
        fileTree("../../data/play") { include("play-content.json", "ferry-timetable.json", "derived/*.json", "golden/*.json", "tides/*.json", "published/*.json") },
        fileTree("../../docs/play") { include("e2e-ids.env") },
        fileTree("../composeApp/src/commonMain/composeResources/files/play") { include("play-content.json") },
    ).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("playDataFiles")
}
