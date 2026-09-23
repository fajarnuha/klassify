# Klassify

Kotlin Multiplatform DSL and SDK for System One classification, starting with TypeSafe Jev. The repository follows the Gradle module layout of [kson](https://github.com/fajarnuha/kson): a reusable library and a Kotlin/Native CLI.

Licensed under Apache 2.0.

## Kotlin DSL

```kotlin
import com.fajarnuha.klassify.TypeSafeClient
import com.fajarnuha.klassify.choice
import com.fajarnuha.klassify.noul
import com.fajarnuha.klassify.score

enum class Species { DOG, CAT, OTHER }

val childFriendly by noul("Is this pet described as friendly with children?")
val species by choice<Species>("What kind of pet is described?")
val energy by score("How active is this pet?") {
    levels("Mostly relaxed", "Moderately active", "Needs vigorous daily exercise")
}

suspend fun classifyPet(apiKey: String) {
    val client = TypeSafeClient(apiKey)
    try {
        val result = client.evaluate(
            "Playful terrier who loves kids and needs a long run every day.",
            childFriendly, species, energy,
        )
        println(result[species].value)              // Species.DOG
        println(result[childFriendly].probability)  // 0.0..1.0
        println(result[energy].value)               // weighted score
    } finally {
        client.close()
    }
}
```

The delegated property name becomes the Jev question ID. Enum entry names become Choice options; `result[species]` returns an enum value with confidence and probabilities. `evaluate` also accepts a `JsonElement` state, such as `buildJsonObject { ... }`. JSON recipes and MCP requests use the same [TypeSafe HTTP format](https://docs.typesafe.ai/api).

## Modules

- `klassify-sdk`: common Kotlin DSL, Ktor client, coroutine based `suspend` evaluation, and typed answers. Targets JVM, macOS, Linux, Windows, and iOS.
- `klassify-cli`: Kotlin/Native executable using Clikt and Mordant. Targets macOS, Linux, and Windows.

## CLI

Install the macOS CLI with Homebrew:

```sh
brew install fajarnuha/tools/klassify
```

Or set `TYPESAFE_API_KEY`, then build and run on this Mac:

```sh
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer ./gradlew :klassify-cli:linkDebugExecutableMacosArm64
./klassify-cli/build/bin/macosArm64/debugExecutable/klassify.kexe run --recipe examples/pet.json
```

The recipe contains a `questions` object, optional `model`, and optional `state`. Pass `--text '...'` to override with text or `--state-json state.json` (`-j state.json`) for structured state. If neither the command nor recipe provides state, the CLI reads stdin. It prints the complete TypeSafe response as JSON.

Run `klassify mcp` for a stdio MCP server. It exposes one `classify` tool with `state`, `questions`, and optional `model` arguments. The tool returns the full TypeSafe response as structured content. MCP clients should launch the executable with `TYPESAFE_API_KEY` in its environment; stdout is reserved for protocol messages.

## JitPack

For a JVM or Android project, add the JitPack repository and SDK dependency:

```kotlin
repositories { maven("https://jitpack.io") }
dependencies { implementation("com.github.fajarnuha:klassify:v0.1.1") }
```

In a recent Gradle project, put the repository in `dependencyResolutionManagement.repositories` in `settings.gradle.kts`. JitPack serves the JVM variant of `klassify-sdk` at the repository coordinate above. Native consumers need a repository carrying the native variants.

## Verify

```sh
./gradlew :klassify-sdk:jvmTest :klassify-cli:compileKotlinMacosArm64
```
