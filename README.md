# Klassify

Klassify classifies text, images, and JSON. The CLI uses TypeSafe Jev and runs on macOS, Linux, and Windows. The Kotlin Multiplatform SDK also supports OpenRouter, OpenAI, and Google Gemini. It uses Ktor and Kotlin coroutines and runs on JVM, macOS, Linux, Windows, and iOS.

## CLI

### Install

On macOS, install with Homebrew.

```sh
brew install fajarnuha/tools/klassify
```

On glibc-based Linux for x64 or ARM64, use the release installer.

```sh
curl -fsSL https://github.com/fajarnuha/klassify/releases/latest/download/install.sh | sh
```

The installer checks the archive's SHA-256 checksum and installs `klassify` in `~/.local/bin` by default. Set `KLASSIFY_INSTALL_DIR` to install elsewhere. Make sure that directory is in your `PATH`. If you want to read the script before running it, download `install.sh` from the [latest release](https://github.com/fajarnuha/klassify/releases/latest) and run `sh install.sh` after reviewing it.

Run `klassify --version` or `klassify -v` to check the installed version.

### Try it

Set `TYPESAFE_API_KEY` to your TypeSafe API key. Download the example recipe into your current directory and run it with your own text.

```sh
curl -fL https://raw.githubusercontent.com/fajarnuha/klassify/main/examples/pet.json -o pet.json
klassify run --recipe pet.json --text "A gentle cat that likes children and naps all day."
```

`--text` replaces the sample state in the recipe. The CLI prints a JSON object with an `answers` field. Look for the pet type in `species.choice`, the probability that the pet is friendly with children in `childFriendly.noul`, and the activity score in `energy.score`.

Use `noul` to ask a yes-or-no question without a recipe. It prints the probability that the answer is true, from 0 to 1.

```sh
klassify noul -q "Is a tomato a fruit?"
```

### Run recipes

A recipe needs a `questions` object and can also include `model` and `state`. Use `--text '...'` or `--state-json state.json` to override the recipe's state. Use `-j` as shorthand for `--state-json`. If neither the command nor the recipe supplies state, the CLI reads it from stdin. It prints the TypeSafe response as JSON.

Pass a file path to `--recipe`. To run a recipe by name from any directory, put it in `KLASSIFY_WORKDIR` and pass its name without the `.json` extension.

```sh
export KLASSIFY_WORKDIR="$HOME/klassify-recipes"
klassify run abc -t "Text to classify"
```

Use `--field` to print one value from the response.

```sh
klassify run --recipe pet.json -t "A quiet cat" --field answers.species.choice
```

Use `recipe check` to validate a recipe without making an API request.

```sh
klassify recipe check --recipe pet.json
```

### Run a batch or evaluate a recipe

Use `batch` to run a recipe on several inputs. It reads JSON Lines. Each line needs an `id` and a `state`, which can be a string or another JSON value.

```sh
printf '%s\n' \
  '{"id":"a","state":"A playful terrier"}' \
  '{"id":"b","state":"A quiet cat"}' |
  klassify batch --recipe pet.json --output results.jsonl
```

`batch` makes one request at a time and writes one result per nonblank input line. Each output line contains the input `id` and either `result` or `error`. The command exits with a nonzero status if any input fails. `--output` replaces an existing file.

To evaluate a recipe against labeled data, add cases like this to `cases.jsonl`.

```json
{"state":"A playful terrier that loves children","expected":{"species":"DOG","childFriendly":true,"energy":2}}
```

Then run `klassify eval --recipe pet.json --cases cases.jsonl`. Use option names for Choice, booleans for Noul, and numbers on the recipe's zero-based scale for Score. The summary reports accuracy for Choice and Noul and mean absolute error for Score. Use `--threshold` to change the Noul cutoff from its default of 0.5.

### Save a yes-or-no question

Use `noul set` to save a question, then run `noul -t` with each input.

```sh
klassify noul set -q "Is this a fruit?"
klassify noul -t "A tomato"
```

`noul set` creates the config directory as needed and writes `noul.json` without calling TypeSafe. It replaces the previously saved question. Use `KLASSIFY_WORKDIR` to choose where the CLI stores the file. The default directory depends on your operating system.

- macOS uses `~/Library/Application Support/klassify`.
- Linux uses `${XDG_CONFIG_HOME:-$HOME/.config}/klassify`.
- Windows uses `%APPDATA%\klassify`.

### Run an MCP server

Run `klassify mcp` to start an MCP server over stdio. Its `classify` tool takes `state` and `questions`, plus an optional `model`. The tool returns the TypeSafe response as structured content. Set `TYPESAFE_API_KEY` in the MCP client's environment. The server reserves stdout for protocol messages.

## Add the SDK

Add Maven Central to your Gradle repositories and declare the SDK dependency.

```kotlin
repositories { mavenCentral() }
dependencies { implementation("com.fajarnuha.klassify:klassify-sdk:0.4.0") }
```

If your project uses `dependencyResolutionManagement`, add `mavenCentral()` in `settings.gradle.kts`. For Kotlin Multiplatform, declare the dependency in the source set you want to use, such as `commonMain`.

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

Klassify uses the property name as the question ID and the enum names as `choice` options. `result[species]` contains the selected value, its confidence, and the probability of each option. Pass a `JsonElement` to `evaluate` to classify structured data. JSON recipes and MCP requests use the [TypeSafe HTTP format](https://docs.typesafe.ai/api).

All four SDK clients implement `ClassificationClient` and return the same typed Noul, Choice, and Score answers.

| Client | Default model | API |
| --- | --- | --- |
| `TypeSafeClient` | `jev-latest` | [TypeSafe System One](https://docs.typesafe.ai/api) |
| `OpenRouterClient` | `typesafe/jev-1.13` | [OpenRouter Decisions](https://openrouter.ai/docs/guides/community/multimodal-decisions) |
| `OpenAIClient` | `gpt-6-luna` | [OpenAI Decisions](https://developers.openai.com/api/docs/guides/decisions) |
| `GoogleClient` | `gemini-decision-1` | [Gemini Decisions](https://ai.google.dev/api/decisions) |

Pass your provider's API key to the client constructor. To change the model, pass `model` when you evaluate a `QuestionSet`. For example, OpenRouter accepts `google/gemini-decision-1` and `openai/gpt-6-luna-decisions`. The clients make up to three attempts on HTTP 429 or 529. If a request fails, the client throws a provider-specific exception with `status` and `responseBody`. Calling `close()` also closes the underlying Ktor client.

### Image input

Use `imageInput` with encoded PNG or JPEG bytes. Pass the image directly for an image-only decision, or place one or more images in a top-level JSON array alongside text.

```kotlin
import com.fajarnuha.klassify.ClassificationClient
import com.fajarnuha.klassify.imageInput
import com.fajarnuha.klassify.noul
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

suspend fun collarProbability(client: ClassificationClient, photoBytes: ByteArray): Double {
    val hasCollar by noul("Is the dog wearing a collar?")
    val photo = imageInput(photoBytes, "image/jpeg")
    val input = JsonArray(listOf(JsonPrimitive("Inspect the dog in this photo."), photo))
    return client.evaluate(input, hasCollar)[hasCollar].probability
}
```

For JSON recipes or CLI `--state-json`, represent an image as `{"type":"image_url","image_url":{"url":"data:image/jpeg;base64,..."}}`. TypeSafe image input is experimental. Its client sends the required opt-in header automatically. [TypeSafe image format and limits](https://docs.typesafe.ai/images)

Licensed under the [Apache License 2.0](LICENSE).
