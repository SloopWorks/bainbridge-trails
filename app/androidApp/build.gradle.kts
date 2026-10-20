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

android {
    namespace = "com.bainbridgetrails.app"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.bainbridgetrails.app"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
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
        }
        getByName("release") {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
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
