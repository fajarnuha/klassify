plugins { alias(libs.plugins.kotlin.multiplatform) }

kotlin {
    listOf(macosArm64(), macosX64(), linuxX64(), linuxArm64(), mingwX64()).forEach { target ->
        target.binaries.executable {
            baseName = "klassify"
            entryPoint = "com.fajarnuha.klassify.cli.main"
        }
    }
    sourceSets {
        commonMain.dependencies {
            implementation(project(":klassify-sdk"))
            implementation(libs.clikt)
            implementation(libs.mordant)
        }
        nativeMain.dependencies { implementation(libs.coroutines.core) }
        commonTest.dependencies { implementation(libs.kotlin.test) }
    }
}
