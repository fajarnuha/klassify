val versionPattern = Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)")
val versionName = providers.gradleProperty("VERSION_NAME").get()
require(versionPattern.matches(versionName)) { "VERSION_NAME must be major.minor.patch" }
allprojects { version = versionName }

val versionFile = rootProject.file("gradle.properties")
val versionBump = providers.gradleProperty("versionBump").orElse("minor")

tasks.register("bumpVersion") {
    group = "versioning"
    description = "Bump VERSION_NAME; use -PversionBump=major|minor|patch (default: minor)"
    doLast {
        val part = versionBump.get()
        val index = listOf("major", "minor", "patch").indexOf(part)
        require(index >= 0) { "versionBump must be major, minor, or patch" }
        val contents = versionFile.readText()
        val entry = Regex("(?m)^VERSION_NAME=([^\\r\\n]*)").findAll(contents).single()
        val current = entry.groupValues[1]
        require(versionPattern.matches(current)) { "VERSION_NAME must be major.minor.patch" }
        val parts = current.split('.').map(String::toInt).toMutableList()
        parts[index] = Math.incrementExact(parts[index])
        for (i in index + 1..2) parts[i] = 0
        val next = parts.joinToString(".")
        versionFile.writeText(contents.replaceRange(entry.range, "VERSION_NAME=$next"))
        logger.lifecycle("Version: $current -> $next")
    }
}
