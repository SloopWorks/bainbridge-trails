rootProject.name = "BainbridgeTrails"
// No TYPESAFE_PROJECT_ACCESSORS: the feature preview applies to included builds too, and the debug drawer's
// root project "debugdrawer" clashes with its ":debugdrawer" module there. Use project(":x").

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

include(":composeApp")
include(":androidApp")
include(":coverage")
include(":coverage-eval")
include(":tracking")

// SloopWorks debug drawer (debug builds only), built from a source checkout: -PdebugDrawerPath=<dir>, else
// ../../debugdrawer (a sibling of this repo). The published AARs are minSdk 33 and need GitHub Packages credentials,
// and an includeBuild of the checkout fails here (its AGP 9.2.1 / Kotlin 2.3 plugins cannot share a build tree with
// ours), so debugdrawer-source/ compiles the checkout's sources with this build's toolchain instead.
// Without a checkout (CI, a fresh clone of this public repo) the in-repo no-op :debugdrawer-shim stands in.
// -PdebugDrawer=off forces the shim even when a checkout exists.
val debugDrawerDir = file(providers.gradleProperty("debugDrawerPath").orNull ?: "../../debugdrawer")
val useDebugDrawer = providers.gradleProperty("debugDrawer").orNull != "off" &&
    debugDrawerDir.resolve("debugdrawer/src/commonMain").isDirectory
if (useDebugDrawer) {
    include(":debugdrawer", ":debugdrawer-noop")
    project(":debugdrawer").projectDir = file("debugdrawer-source/debugdrawer")
    project(":debugdrawer-noop").projectDir = file("debugdrawer-source/debugdrawer-noop")
} else {
    include(":debugdrawer-shim")
}
