plugins {
    alias(libs.plugins.kotlinJvm)
    application
}

dependencies {
    implementation(project(":coverage"))
    implementation(project(":tracking")) // `session`: SessionStore.parse + Gating
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlin.test)
}

application {
    mainClass.set("com.bainbridgetrails.coverage.eval.MainKt")
}

// CLI paths (--data ../data/dist, ../data/tracks/...) are relative to the Gradle root app/
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}

tasks.test {
    useJUnit()
}
