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
// -Pplay.noData=true runs the dataset tests on the committed fixture (src/jvmTest/resources/fixture/places.json, written
// by `node scripts/build-play-content.mjs --fixture`) even where data/dist and data/roads/dist are built, as CI does.
tasks.named<Test>("jvmTest") {
    val updateGolden = providers.gradleProperty("play.updateGolden").orNull == "true"
    systemProperty("play.updateGolden", updateGolden.toString())
    val noData = providers.gradleProperty("play.noData").orNull == "true"
    systemProperty("play.noData", noData.toString())
    if (updateGolden) outputs.upToDateWhen { false }
    testLogging {
        events("skipped") // nothing should skip: dataset tests fall back to the committed fixture (review P1-7)
    }
    // Tests read these by relative path; declare them so a cached result from a checkout with the data is never
    // reused by one without it (and vice versa). Missing files are fine (empty file trees). The fixture is a test
    // resource (already an input); listed for clarity.
    inputs.files(
        fileTree("src/jvmTest/resources/fixture") { include("*.json") },
        fileTree("../../data/dist") { include("trails.json", "coverage_segments.json", "trailheads.json") },
        fileTree("../../data/roads/dist") { include("roads.json", "coverage_segments.json") },
        fileTree("../../data/play") { include("play-content.json", "ferry-timetable.json", "derived/*.json", "golden/*.json", "tides/*.json", "published/*.json") },
        fileTree("../../docs/play") { include("e2e-ids.env") },
        fileTree("../composeApp/src/commonMain/composeResources/files/play") { include("play-content.json") },
        // TrailRewardsGoldenTest: counted segment lengths and trail membership the parity fixture was computed from
        fileTree("../../backend/src/data") { include("segments.json") },
    ).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("playDataFiles")
}
