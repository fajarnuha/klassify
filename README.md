# Klassify

`klassify` is a Kotlin Multiplatform SDK and DSL for System One classification, starting with TypeSafe Jev.

## Modules

- `klassify-sdk`: Kotlin DSL, Ktor HTTP client, coroutine `suspend` functions, and typed answers. Targets JVM, macOS, Linux, Windows, and iOS.
- `klassify-cli`: Kotlin/Native executable for classification tasks and MCP. Targets macOS, Linux, and Windows.

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

The property name becomes the Jev question ID. For a `choice` question, enum names become options. `result[species]` contains the selected value, confidence, and option probabilities. Pass a `JsonElement` to `evaluate` for structured state. JSON recipes and MCP requests use the [TypeSafe HTTP format](https://docs.typesafe.ai/api).

## CLI

Install on macOS with Homebrew:

```sh
brew install fajarnuha/tools/klassify
```

On glibc-based Linux (x64 or ARM64), install the latest release:

```sh
curl -fsSL https://github.com/fajarnuha/klassify/releases/latest/download/install.sh | sh
```

The installer checks the archive's SHA-256 checksum and puts `klassify` in `~/.local/bin`. To review the script first, download `install.sh` from the [latest release](https://github.com/fajarnuha/klassify/releases/latest), then run `sh install.sh`. Set `KLASSIFY_INSTALL_DIR` to install elsewhere. Make sure the install directory is in your `PATH`.

### Try it

From a checkout of this repository, set `TYPESAFE_API_KEY` and run the included [pet recipe](examples/pet.json):

```sh
klassify run --recipe examples/pet.json --text "A gentle cat that likes children and naps all day."
```

`--text` replaces the sample state in the recipe. The CLI prints one JSON object. Look under `answers` for `species.choice` (the pet type), `childFriendly.noul` (a probability from 0 to 1), and `energy.score` (an activity score).

The recipe needs a `questions` object and can include `model` or `state`. Override its state with `--text '...'` or `--state-json state.json` (short form: `-j state.json`). If neither the command nor the recipe supplies state, the CLI reads stdin. It prints the TypeSafe response as JSON.

Run `klassify mcp` to start an MCP server over stdio. Its `classify` tool takes `state` and `questions`, plus an optional `model`. The tool returns the TypeSafe response as structured content. Set `TYPESAFE_API_KEY` in the MCP client's environment. The server reserves stdout for protocol messages.

## JitPack

Add JitPack and the SDK to a JVM or Android project:

```kotlin
repositories { maven("https://jitpack.io") }
dependencies { implementation("com.github.fajarnuha:klassify:v0.1.2") }
```

If your project uses `dependencyResolutionManagement`, add JitPack in `settings.gradle.kts`. This dependency resolves the JVM variant of `klassify-sdk`. Native consumers need a repository that publishes the native variants.

## Build from source

On Apple Silicon, set `TYPESAFE_API_KEY` and build the CLI:

```sh
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer ./gradlew :klassify-cli:linkDebugExecutableMacosArm64
./klassify-cli/build/bin/macosArm64/debugExecutable/klassify.kexe run --recipe examples/pet.json
```

To run SDK tests and compile the CLI on macOS:

```sh
./gradlew :klassify-sdk:jvmTest :klassify-cli:compileKotlinMacosArm64
```

Licensed under the [Apache License 2.0](LICENSE).
