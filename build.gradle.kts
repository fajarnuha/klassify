plugins {
    id("com.vanniktech.maven.publish.base") version "0.37.0" apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
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
