plugins { alias(libs.plugins.kotlin.multiplatform) }

val runnerProject = project(":klassify-recipe-runner")
val runnerLib = runnerProject.layout.buildDirectory.dir("install/klassify-recipe-runner/lib")
val cliVersion = project.version.toString()

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
    applyDefaultHierarchyTemplate()
    listOf(macosArm64(), macosX64(), linuxX64(), linuxArm64(), mingwX64()).forEach { target ->
        target.binaries.executable {
            baseName = "klassify"
            entryPoint = "com.fajarnuha.klassify.cli.main"
            val bundleRunner = tasks.register<Sync>("bundleRecipeRunner${linkTaskName.removePrefix("link")}") {
                dependsOn(":klassify-recipe-runner:installDist")
                from(runnerLib) { into("$cliVersion/lib") }
                into(outputDirectory.resolve("klassify-recipe-runner"))
            }
            linkTaskProvider.configure { finalizedBy(bundleRunner) }
        }
    }
    sourceSets {
        val unixMain by creating { dependsOn(nativeMain.get()) }
        macosMain.get().dependsOn(unixMain)
        linuxMain.get().dependsOn(unixMain)
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
