import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    `maven-publish`
}

kotlin {
    explicitApi()
    jvm { compilerOptions { jvmTarget.set(JvmTarget.JVM_11) } }
    macosArm64()
    macosX64()
    linuxX64()
    linuxArm64()
    mingwX64()
    iosArm64()
    iosSimulatorArm64()
    iosX64()

    sourceSets {
        commonMain.dependencies {
            api(libs.coroutines.core)
            api(libs.serialization.json)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.engine.defaults)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.ktor.client.mock)
        }
    }
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("klassify-sdk")
            description.set("Kotlin Multiplatform DSL and SDK for TypeSafe System One")
            url.set("https://github.com/fajarnuha/klassify")
            licenses {
                license {
                    name.set("Apache-2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                }
            }
        }
    }
}
