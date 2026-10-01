plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
}

apply(from = "gradle/versioning.gradle.kts")

allprojects {
    group = "com.fajarnuha.klassify"
}
