// Shipyard Deploy (dev-build distribution, see ../.shipyard-deploy.yaml) is opt-in so a plain
// clone builds without it: pass -PshipyardDeployPluginRepository=<maven repo holding the plugin>.
buildscript {
    providers.gradleProperty("shipyardDeployPluginRepository").orNull?.let { repository ->
        repositories {
            maven { url = uri(repository); content { includeGroup("works.sloop.shipyard") } }
            mavenCentral()
        }
        dependencies { classpath("works.sloop.shipyard:shipyard-deploy-gradle-plugin:0.1.0") }
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

dependencies {
    implementation(projects.composeApp)
    implementation(libs.androidx.activity.compose)
}
