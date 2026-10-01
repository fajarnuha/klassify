plugins { alias(libs.plugins.kotlin.multiplatform) }

val versionSourceDir = layout.buildDirectory.dir("generated/version")
val generateCliVersion = tasks.register("generateCliVersion") {
    inputs.property("version", project.version.toString())
    outputs.dir(versionSourceDir)
    doLast {
        val source = versionSourceDir.get().file("com/fajarnuha/klassify/cli/Version.kt").asFile
        source.parentFile.mkdirs()
        source.writeText("package com.fajarnuha.klassify.cli\n\ninternal const val VERSION = \"${inputs.properties["version"]}\"\n")
    }
}

kotlin {
    listOf(macosArm64(), macosX64(), linuxX64(), linuxArm64(), mingwX64()).forEach { target ->
        target.binaries.executable {
            baseName = "klassify"
            entryPoint = "com.fajarnuha.klassify.cli.main"
        }
    }
    sourceSets {
        commonMain { kotlin.srcDir(generateCliVersion) }
        commonMain.dependencies {
            implementation(project(":klassify-sdk"))
            implementation(libs.clikt)
            implementation(libs.mordant)
        }
        nativeMain.dependencies { implementation(libs.coroutines.core) }
        commonTest.dependencies { implementation(libs.kotlin.test) }
    }
}
