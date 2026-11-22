import groovy.json.JsonSlurper

plugins {
    id("com.vanniktech.maven.publish.base") version "0.37.0" apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}

apply(from = "gradle/versioning.gradle.kts")

allprojects {
    group = "com.fajarnuha.klassify"
}

subprojects {
    pluginManager.withPlugin("maven-publish") {
        pluginManager.apply("com.vanniktech.maven.publish.base")
        val repositoryUrl = "https://github.com/fajarnuha/klassify"
        extensions.configure<PublishingExtension> {
            publications.withType<MavenPublication>().configureEach {
                val publicationName = name
                val documentationJar = tasks.register<Jar>("${publicationName}PublicationJavadocJar") {
                    archiveAppendix.set(publicationName)
                    archiveClassifier.set("javadoc")
                    from(rootProject.file("README.md"))
                }
                artifact(documentationJar)
                pom {
                    developers {
                        developer {
                            id.set("fajarnuha")
                            name.set("Fajar Ulin Nuha")
                            url.set("https://fajarnuha.com")
                        }
                    }
                    scm {
                        url.set(repositoryUrl)
                        connection.set("scm:git:$repositoryUrl.git")
                        developerConnection.set("scm:git:ssh://git@github.com/fajarnuha/klassify.git")
                    }
                }
            }
        }
        extensions.configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
            publishToMavenCentral()
            if (providers.gradleProperty("signingInMemoryKey").isPresent) {
                signAllPublications()
            }
        }
    }
}

fun registerRecipePack(taskName: String, directory: String, archiveBaseName: String, companion: String) =
    tasks.register<Zip>(taskName) {
        val manifest = layout.projectDirectory.file("$directory/pack.json")
        archiveFileName.set(providers.fileContents(manifest).asText.map { content ->
            val packVersion = (JsonSlurper().parseText(content) as Map<*, *>)["version"] as? String
                ?: error("Recipe pack manifest at ${manifest.asFile} needs a version")
            "$archiveBaseName-$packVersion.zip"
        })
        destinationDirectory.set(layout.buildDirectory.dir("recipe-packs"))
        from(directory)
        from("LICENSE")
        from(companion) { into("recipes") }
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }

val starterRecipePack = registerRecipePack(
    "starterRecipePack", "recipe/starter", "klassify-starter-recipes", "recipe/email-triage.json",
)
val androidDevRecipePack = registerRecipePack(
    "androidDevRecipePack", "recipe/android-dev", "klassify-android-dev-recipes", "recipe/app-review-triage.json",
)
tasks.register("recipePacks") { dependsOn(starterRecipePack, androidDevRecipePack) }

tasks.register<Sync>("releaseRecipePacks") {
    into(layout.buildDirectory.dir("release-recipe-packs"))
    val starter = starterRecipePack.flatMap { it.archiveFile }
    val androidDev = androidDevRecipePack.flatMap { it.archiveFile }
    from(starter, androidDev)
    from(starter) { rename { "klassify-starter-recipes.zip" } }
    from(androidDev) { rename { "klassify-android-dev-recipes.zip" } }
}
