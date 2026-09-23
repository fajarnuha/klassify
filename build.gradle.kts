plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
}

allprojects {
    group = providers.gradleProperty("GROUP").getOrElse("com.fajarnuha.klassify")
    version = providers.gradleProperty("VERSION_NAME").getOrElse("0.1.2")
}
