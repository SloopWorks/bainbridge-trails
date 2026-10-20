// Shipyard Deploy (dev-build distribution, see ../.shipyard-deploy.yaml) is opt-in so a plain
// clone builds without it: pass -PshipyardDeployPluginRepository=<maven repo holding the plugin>.
buildscript {
    providers.gradleProperty("shipyardDeployPluginRepository").orNull?.let { repository ->
        repositories {
            maven { url = uri(repository); content { includeGroup("works.sloop.shipyard") } }
            mavenCentral()
        }
        dependencies { classpath("works.sloop.shipyard:shipyard-deploy-gradle-plugin:0.1.0-bainbridge.0597dd4") }
    }
}

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

if (providers.gradleProperty("shipyardDeployPluginRepository").isPresent) {
    apply(plugin = "works.sloop.shipyard.deploy")
}

// Trailhead geofence prototype (background location + boot receiver): debug builds only, and only with
// -PgeofencePrototype=true (default off: a debug APK requesting ACCESS_BACKGROUND_LOCATION trips Play Protect's
// INSTALL_FAILED_VERIFICATION_FAILURE on sideloads). Adds src/geofencePrototype/AndroidManifest.xml to the debug
// variant; without it the app hides the opt-in (AndroidGeofences.declared is false). Never in release.
val geofencePrototype = providers.gradleProperty("geofencePrototype").orNull == "true"

// Release signing: keystore + passwords from Gradle properties or the environment (CI secrets), never from the repo.
// All four present -> release is signed; otherwise assemble<Goal>Release produces androidApp-<goal>-release-unsigned.apk.
// -PreleaseSignWithDebugKey=true signs release with the local debug key instead (on-device testing of an R8 build
// only; such an APK must never be distributed).
fun releaseSetting(name: String): String? =
    (providers.gradleProperty(name).orNull ?: providers.environmentVariable(name).orNull)?.takeIf { it.isNotBlank() }
val releaseStoreFile = releaseSetting("BT_RELEASE_STORE_FILE")
val releaseStorePassword = releaseSetting("BT_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = releaseSetting("BT_RELEASE_KEY_ALIAS")
val releaseKeyPassword = releaseSetting("BT_RELEASE_KEY_PASSWORD")
val hasReleaseKey = listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword).all { it != null }
val releaseSignWithDebugKey = providers.gradleProperty("releaseSignWithDebugKey").orNull == "true"

// Version: -PbtVersionCode=<int> -PbtVersionName=<string> (CI/release), else the defaults below.
val btVersionCode = providers.gradleProperty("btVersionCode").orNull?.toInt() ?: 1
val btVersionName = providers.gradleProperty("btVersionName").orNull ?: "1.0"

android {
    namespace = "com.bainbridgetrails.app"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.bainbridgetrails.app"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = btVersionCode
        versionName = btVersionName
        // Google OAuth *web* client id for Sign in with Google (Credential Manager `serverClientId`; the backend's
        // GOOGLE_CLIENT_IDS must include it). `-PgoogleWebClientId=…` or gradle.properties; empty = the Google
        // button shows "not configured" (debug builds still offer dev sign-in).
        val googleWebClientId = providers.gradleProperty("googleWebClientId").orElse("").get()
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"" + googleWebClientId.replace("\"", "") + "\"")
    }
    // RD-1: one app per goal. The flavour fixes the DatasetProfile (BuildConfig.GOAL, read by BtApplication) and the
    // launcher label (src/<flavour>/res). Variants: trailsDebug, trailsRelease, roadsDebug, roadsRelease.
    flavorDimensions += "goal"
    productFlavors {
        create("trails") {
            dimension = "goal"
            applicationId = "com.bainbridgetrails.app"
            buildConfigField("String", "GOAL", "\"trails\"")
        }
        create("roads") {
            dimension = "goal"
            applicationId = "com.bainbridgetrails.roads"
            buildConfigField("String", "GOAL", "\"roads\"")
        }
    }
    buildFeatures {
        buildConfig = true // VERSION_NAME / BUILD_TYPE for the debug drawer's build info
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }
    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".dev"
            buildConfigField("boolean", "GEOFENCE_PROTOTYPE", geofencePrototype.toString())
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("boolean", "GEOFENCE_PROTOTYPE", "false")
            signingConfig = when {
                releaseSignWithDebugKey -> signingConfigs.getByName("debug")
                hasReleaseKey -> signingConfigs.getByName("release")
                else -> null
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Bundled datasets per flavour (scripts/sync-app-assets.sh installs both into composeApp's resources). The roads APK
// drops the trail dataset (its coverage_segments already carries the trail competitors it needs, RD-2b); trailsRelease
// drops the roads dataset. trailsDebug keeps both for the debug drawer's dataset switch. Patterns match file names.
val trailDatasetAssets = listOf("trails.json", "coverage_segments.json", "dataset.bin", "trails.pmtiles")
val roadDatasetAssets = listOf("roads.json", "roads_coverage_segments.json", "roads_dataset.bin", "roads.pmtiles", "roads_road_ends.json")

androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        if (geofencePrototype) variant.sources.manifests.addStaticManifestFile("src/geofencePrototype/AndroidManifest.xml")
    }
    onVariants { variant ->
        val goal = variant.productFlavors.single { it.first == "goal" }.second
        val drop = when {
            goal == "roads" -> trailDatasetAssets
            variant.buildType == "release" -> roadDatasetAssets
            else -> emptyList()
        }
        variant.androidResources.ignoreAssetsPatterns.addAll(drop)
    }
}

// Debug drawer: real drawer (debug) / its noop twin (release), compiled from a checkout's sources when
// settings.gradle.kts found one; otherwise the in-repo no-op :debugdrawer-shim for both. Never a published
// artifact (those are minSdk 33).
val debugDrawerShim = rootProject.findProject(":debugdrawer-shim")

dependencies {
    implementation(project(":composeApp"))
    implementation(project(":tracking")) // debug tools read/write the coverage store
    implementation(libs.androidx.activity.compose)
    implementation(compose.material3)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.core)
    if (debugDrawerShim != null) {
        implementation(debugDrawerShim)
    } else {
        debugImplementation(project(":debugdrawer"))
        releaseImplementation(project(":debugdrawer-noop"))
    }
}
