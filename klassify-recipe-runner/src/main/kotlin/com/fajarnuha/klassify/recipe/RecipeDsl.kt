package com.fajarnuha.klassify.recipe

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE

/** Defines the questions and inputs to prepare for one invocation. */
fun recipe(args: Array<String>, model: String = "jev-latest", prepare: RecipeContext.() -> Unit) =
    runRecipe(args, model, prepare, null)

/** Optionally proposes actions from completed classifications. The CLI handles confirmation. */
fun recipe(
    args: Array<String>,
    model: String = "jev-latest",
    postprocess: PostprocessContext.() -> Unit,
    prepare: RecipeContext.() -> Unit,
) = runRecipe(args, model, prepare, postprocess)

private fun runRecipe(
    args: Array<String>,
    model: String,
    prepare: RecipeContext.() -> Unit,
    postprocess: (PostprocessContext.() -> Unit)?,
) {
    val request = Json.parseToJsonElement(Files.readString(Path.of(args[0]))).jsonObject
    val options = request.getValue("options").jsonArray.map { it.jsonPrimitive.content }
    val results = request["results"]?.jsonArray
    val output = if (results == null) {
        val context = RecipeContext(options, request["questions"]?.jsonObject, request["sampleState"])
        context.prepare()
        context.result(model, postprocess != null)
    } else {
        val hook = postprocess ?: error("This recipe has no postprocess hook")
        val context = PostprocessContext(options, results.map { row ->
            val item = row.jsonObject
            RecipeResult(item.string("id"), item.getValue("result").jsonObject)
        })
        context.hook()
        context.result()
    }
    Files.writeString(Path.of(args[1]), output.toString(), CREATE_NEW, WRITE)
}

data class RecipeResult(val id: String, val result: JsonObject) {
    fun choice(question: String): String = result.getValue("answers").jsonObject
        .getValue(question).jsonObject.string("choice")
}

class PostprocessContext internal constructor(tokens: List<String>, val results: List<RecipeResult>) {
    val workingDirectory: Path = Path.of("").toAbsolutePath()
    private val options = parseOptions(tokens)
    private var proposal: JsonObject? = null

    fun pathOption(name: String): Path? = options[name]?.let(workingDirectory::resolve)

    fun requiredCsvOption(name: String): List<String> =
        csvValues(name, options[name] ?: error("Pass --$name"))

    fun proposeFileMoves(directory: Path, folders: List<String>, destinations: Map<String, String>) {
        require(proposal == null) { "Only one file move plan can be proposed" }
        proposal = buildJsonObject {
            put("directory", directory.toAbsolutePath().toString())
            put("folders", JsonArray(folders.map(::JsonPrimitive)))
            put("destinations", JsonObject(destinations.mapValues { JsonPrimitive(it.value) }))
        }
    }

    internal fun result(): JsonObject = proposal ?: JsonObject(emptyMap())
}

class RecipeContext internal constructor(
    tokens: List<String>,
    private val template: JsonObject?,
    private val sampleState: JsonElement?,
) {
    val workingDirectory: Path = Path.of("").toAbsolutePath()
    private val options = parseOptions(tokens)
    private val questions = linkedMapOf<String, JsonElement>()
    private val items = linkedMapOf<String, JsonElement>()

    fun option(name: String): String? = options.remove(name)

    fun requiredOption(name: String): String = option(name) ?: error("Pass --$name")

    fun pathOption(name: String): Path? = option(name)?.let { workingDirectory.resolve(it) }

    fun requiredCsvOption(name: String): List<String> = csvValues(name, requiredOption(name))

    fun choice(id: String, instructions: String, options: List<String>) {
        choice(id, instructions, options.associateWith { null })
    }

    fun choice(id: String, instructions: String, options: Map<String, String?>) {
        require(id !in questions) { "Duplicate question '$id'" }
        questions[id] = buildJsonObject {
            put("type", "choice")
            put("instructions", instructions)
            put("criteria", buildJsonObject {
                options.forEach { (name, description) ->
                    put(name, description?.let(::JsonPrimitive) ?: JsonNull)
                }
            })
        }
    }

    /** Adds questions from the optional JSON recipe alongside this script in the pack. */
    fun questionsFromPack() {
        val definitions = template ?: error("No companion JSON recipe in the pack")
        require(questions.keys.none(definitions::containsKey)) { "Duplicate question in companion JSON recipe" }
        questions.putAll(definitions)
    }

    fun sampleStateFromPack(): JsonElement = sampleState ?: error("No sample state in the companion JSON recipe")

    fun item(id: String, state: JsonElement) {
        require(id.isNotBlank() && id !in items) { "Missing or duplicate item ID '$id'" }
        items[id] = state
    }

    internal fun result(model: String, hasPostprocess: Boolean): JsonObject {
        require(options.isEmpty()) { "Unknown recipe option --${options.keys.first()}" }
        return buildJsonObject {
            put("model", model)
            if (hasPostprocess) put("postprocess", true)
            put("questions", JsonObject(questions))
            put("items", JsonArray(items.map { (id, state) ->
                buildJsonObject {
                    put("id", id)
                    put("state", state)
                }
            }))
        }
    }
}

private fun csvValues(name: String, value: String): List<String> {
    val values = value.split(',').map(String::trim)
    require(values.all(String::isNotEmpty) && values.distinct().size == values.size) {
        "--$name needs unique, nonblank comma-separated values"
    }
    return values
}

private fun parseOptions(tokens: List<String>): MutableMap<String, String> {
    val options = linkedMapOf<String, String>()
    val iterator = tokens.iterator()
    while (iterator.hasNext()) {
        val token = iterator.next()
        require(token.startsWith("--") && token.length > 2) { "Expected a recipe option, got '$token'" }
        val name = token.substringAfter("--").substringBefore('=')
        val value = if ('=' in token) token.substringAfter('=') else {
            require(iterator.hasNext()) { "Pass a value for --$name" }
            iterator.next().also { require(!it.startsWith("--")) { "Pass a value for --$name" } }
        }
        require(value.isNotBlank()) { "Pass a value for --$name" }
        require(name !in options) { "Duplicate recipe option --$name" }
        options[name] = value
    }
    return options
}
