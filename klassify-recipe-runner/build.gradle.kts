plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(libs.serialization.json)
    implementation(libs.kotlin.compiler.embeddable)
    runtimeOnly(libs.kotlin.scripting.compiler.embeddable)
}

application {
    mainClass.set("com.fajarnuha.klassify.recipe.RunnerKt")
}

distributions {
    main { contents { from(rootProject.file("LICENSE")) } }
}

tasks.distZip {
    archiveFileName.set("klassify-recipe-runner.zip")
    isReproducibleFileOrder = true
    isPreserveFileTimestamps = false
}
