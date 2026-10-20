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

android {
    namespace = "com.bainbridgetrails.app"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.bainbridgetrails.app"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
        // Google OAuth *web* client id for Sign in with Google (Credential Manager `serverClientId`; the backend's
        // GOOGLE_CLIENT_IDS must include it). `-PgoogleWebClientId=…` or gradle.properties; empty = the Google
        // button shows "not configured" (debug builds still offer dev sign-in).
        val googleWebClientId = providers.gradleProperty("googleWebClientId").orElse("").get()
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"" + googleWebClientId.replace("\"", "") + "\"")
    }
    buildFeatures {
        buildConfig = true // VERSION_NAME / BUILD_TYPE for the debug drawer's build info
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".dev"
            buildConfigField("boolean", "GEOFENCE_PROTOTYPE", geofencePrototype.toString())
        }
        getByName("release") {
            isMinifyEnabled = false
            buildConfigField("boolean", "GEOFENCE_PROTOTYPE", "false")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        if (geofencePrototype) variant.sources.manifests.addStaticManifestFile("src/geofencePrototype/AndroidManifest.xml")
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
