# Klassify

Klassify is a Kotlin Multiplatform SDK for classifying text, images, and JSON. It returns typed answers and supports TypeSafe Jev, OpenRouter, OpenAI, and Google Gemini. It uses Ktor and Kotlin coroutines and runs on JVM, macOS, Linux, Windows, and iOS.

## SDK

### Add the SDK

Add Maven Central to your Gradle repositories and declare the SDK dependency.

```kotlin
repositories { mavenCentral() }
dependencies { implementation("com.fajarnuha.klassify:klassify-sdk:0.5.0") }
```

If your project uses `dependencyResolutionManagement`, add `mavenCentral()` in `settings.gradle.kts`. For Kotlin Multiplatform, declare the dependency in the source set you want to use, such as `commonMain`.

### Kotlin DSL

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

Klassify uses the property name as the question ID and the enum names as `choice` options. `result[species]` contains the selected value, its confidence, and the probability of each option. Pass a `JsonElement` to `evaluate` to classify structured data.

All four SDK clients implement `ClassificationClient` and return the same typed Noul, Choice, and Score answers.

| Client | Default model | API |
| --- | --- | --- |
| `TypeSafeClient` | `jev-latest` | [TypeSafe System One](https://docs.typesafe.ai/api) |
| `OpenRouterClient` | `typesafe/jev-1.13` | [OpenRouter Decisions](https://openrouter.ai/docs/guides/community/multimodal-decisions) |
| `OpenAIClient` | `gpt-6-luna` | [OpenAI Decisions](https://developers.openai.com/api/docs/guides/decisions) |
| `GoogleClient` | `gemini-decision-1` | [Gemini Decisions](https://ai.google.dev/api/decisions) |

Pass your provider's API key to the client constructor. To change the model, pass `model` when you evaluate a `QuestionSet`. For example, OpenRouter accepts `google/gemini-decision-1` and `openai/gpt-6-luna-decisions`. The clients make up to three attempts on HTTP 429 or 529. If a request fails, the client throws a provider-specific exception with `status` and `responseBody`. Calling `close()` also closes the underlying Ktor client.

#### Image input

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

TypeSafe image input is experimental. Its client sends the required opt-in header automatically. [TypeSafe image format and limits](https://docs.typesafe.ai/images)

## CLI

The Klassify CLI uses TypeSafe Jev and runs on macOS, Linux, and Windows.

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
curl -fL https://raw.githubusercontent.com/fajarnuha/klassify/main/recipe/pet.json -o pet.json
klassify run --recipe pet.json --text "A gentle cat that likes children and naps all day."
```

`--text` replaces the sample state in the recipe. The CLI prints a JSON object with an `answers` field. Look for the pet type in `species.choice`, the probability that the pet is friendly with children in `childFriendly.noul`, and the activity score in `energy.score`.

Use `noul` to ask a yes-or-no question without a recipe. It prints the probability that the answer is true,     from 0 to 1.

```sh
klassify noul -q "Is a tomato a fruit?"
```

### Run recipes

JSON recipes and MCP requests use the [TypeSafe HTTP format](https://docs.typesafe.ai/api).

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

For JSON recipes or CLI `--state-json`, represent an image as `{"type":"image_url","image_url":{"url":"data:image/jpeg;base64,..."}}`.

### Triage recipes

From the repository root, run either recipe with its built-in sample. Set `TYPESAFE_API_KEY` before running.

```sh
klassify run --recipe recipe/email-triage.json --field answers.category.choice
klassify run --recipe recipe/app-review-triage.json
```

- [Email triage](recipe/email-triage.json) returns `spam`, `promo`, or `important` in `answers.category.choice`. The `important` label also covers ordinary legitimate inbox mail. For structured input, send `from`, `subject`, and `body` when available.
- [App review triage](recipe/app-review-triage.json) returns a feedback category in `answers.category.choice` and an impact-based priority in `answers.priority.choice`. Priority ranges from `low` through `normal`, `high`, and `urgent`. For structured input, send `text` and include `rating`, `appVersion`, and `platform` when available.

Replace the sample with `--text '...'` or pass a JSON object for one email or review with `--state-json input.json`. For multiple items, use `batch` and put one email or review in each line's `state`.

### Install Kotlin recipe packs

Klassify ships with the Kotlin recipe runner. Kotlin recipes need Java 21 or newer. Run `klassify recipe doctor` to check that the runner is available and that Klassify can find a supported Java installation. If needed, install a Java 21+ JDK and set `JAVA_HOME` or add its `bin` directory to `PATH`. Ordinary CLI commands and JSON recipes work without Java.

```bash
klassify recipe doctor
klassify install klassify-starter
klassify install android-dev
klassify recipe list
```

`klassify install <pack-id>` downloads the latest official pack. `klassify recipe install` is equivalent. Both commands also accept a local ZIP path or HTTPS URL and store the pack in the Klassify config directory under `recipe-packs/`. Set `KLASSIFY_RECIPE_DIR` to use a different location. If `KLASSIFY_WORKDIR` contains a JSON recipe with the same name, that JSON file takes precedence. Install validates the pack. Running an installed recipe executes its Kotlin script with your user permissions, so use packs from sources you trust.

```bash
klassify run sort-files --dir ~/Downloads --folders documents,images,other
klassify run email-triage --from store@example.com --subject "Order shipped" --body "Your package is on its way"
klassify run email-triage --body "Please send the invoice" --criteria billing,support,other
klassify run app-review-triage --reviews reviews.csv --app-version 1.8.0
```

- **Sort files** (`klassify-starter`) reads the immediate regular files in `--dir` (the current directory by default) and sends each filename and extension for classification. The returned folder is a suggestion. The recipe leaves files in place.
- **Email triage** (`klassify-starter`) classifies one email into `spam`, `promo`, or `important`. Supply any of `--from`, `--subject`, and `--body`, or omit them to use the example email. `--criteria` replaces the default categories with comma-separated labels or a JSON object such as `'{"billing":"Invoices or payments","support":"Requests for help"}'`.
- **App review triage** (`android-dev`) accepts UTF-8 CSV with `id`, `text`, `rating`, `appVersion`, and an optional `platform` column. It skips blank reviews, optionally filters by exact app version, and reports malformed rows with their line numbers. The review ID identifies each result.

To move files after reviewing the proposed destinations, use a current Klassify CLI with `klassify-starter` 0.3.0 or later.

```sh
klassify run sort-files --dir ~/Downloads --folders documents,images --apply
```

Klassify lists the proposed moves and waits for an explicit yes in an interactive terminal. It creates destination folders as needed after confirmation and rejects existing destinations. Press Enter to decline.

Each command writes one JSON line per input with an `id` and the TypeSafe `result`. Add `--field answers.folder.choice` to print just the folder choice alongside each ID. Use `--prepare-only` to see the questions and inputs before sending them to TypeSafe.

`klassify recipe list` shows each installed pack's ID, version, and recipe names. Run `klassify recipe update klassify-starter` or `klassify recipe update android-dev` to fetch the latest official pack. If you installed starter pack 0.1.0, update it before installing `android-dev`, so only one pack provides app-review triage. To choose a specific release, pass a local ZIP or HTTPS URL as a second argument. Updates keep the installed version if validation fails. To remove a pack, run `klassify recipe uninstall <pack-id>`.

Third-party packs can enable source-free updates by including an `updateUrl` in each release's `pack.json`. Use a stable HTTPS URL that serves the latest ZIP for that pack.

To install from a ZIP, download a pack ZIP from a Klassify release or build both with `./gradlew recipePacks`. Local builds put the ZIPs under `build/recipe-packs/`, where you can pass their paths to `klassify install`. Use their manifests and scripts as templates for your own versioned pack. [Klassify releases](https://github.com/fajarnuha/klassify/releases) · [Starter pack](recipe/starter/pack.json) · [Android development pack](recipe/android-dev/pack.json)

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

Licensed under the [Apache License 2.0](LICENSE).
