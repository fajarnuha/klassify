plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
}

allprojects {
    group = "com.fajarnuha.klassify"
    version = providers.gradleProperty("VERSION_NAME").getOrElse("0.2.0")
}
