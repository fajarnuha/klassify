package com.fajarnuha.klassify.cli

import com.fajarnuha.klassify.QuestionSet
import com.fajarnuha.klassify.TypeSafeClient
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.danger
import kotlinx.coroutines.runBlocking
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlin.native.OsFamily
import kotlin.native.Platform
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.experimental.ExperimentalNativeApi
import platform.posix.FILE
import platform.posix.EOF
import platform.posix.fclose
import platform.posix.ferror
import platform.posix.fflush
import platform.posix.fgetc
import platform.posix.fopen
import platform.posix.fputs
import platform.posix.fread
import platform.posix.getenv
import platform.posix.mkdir
import platform.posix.access
import platform.posix.F_OK
import platform.posix.stdin
import platform.posix.stdout
import kotlin.system.exitProcess

private const val VERSION = "0.1.3"

private class Klassify : CliktCommand(name = "klassify") {
    override fun run() = Unit
}

private class Run : CliktCommand(name = "run") {
    private val recipeName by argument("recipe", help = "Recipe name in KLASSIFY_WORKDIR").optional()
    private val recipe by option("--recipe", "-r", help = "JSON recipe path")
    private val text by option("--text", "-t", help = "Text state; defaults to recipe state or stdin")
    private val stateJson by option("--state-json", "-j", help = "JSON state file")
    private val apiKey by option("--api-key", help = "TypeSafe API key; defaults to TYPESAFE_API_KEY")
    private val model by option("--model", help = "Override the recipe model")
    private val field by option("--field", help = "Print one dot-separated response field")

    @OptIn(ExperimentalForeignApi::class)
    override fun run() {
        require(text == null || stateJson == null) { "Use either --text or --state-json" }
        val spec = loadRecipe(resolveRecipePath(recipe, recipeName))
        val state: JsonElement = when {
            text != null -> JsonPrimitive(text!!)
            stateJson != null -> Json.parseToJsonElement(readFile(stateJson!!))
            spec.state != null -> spec.state
            else -> JsonPrimitive(readAll(stdin))
        }
        val client = TypeSafeClient(apiKey ?: environmentKey())
        try {
            val result = runBlocking { client.evaluate(state, spec.questions, model ?: spec.model) }
            val selected = field?.let { selectField(result.json, it) } ?: result.json
            writeLine(if (selected is JsonPrimitive && selected.isString) selected.content else selected.toString())
        } finally {
            client.close()
        }
    }
}

private class Batch : CliktCommand(name = "batch") {
    private val recipeName by argument("recipe", help = "Recipe name in KLASSIFY_WORKDIR").optional()
    private val recipe by option("--recipe", "-r", help = "JSON recipe path")
    private val input by option("--input", "-i", help = "JSONL input; defaults to stdin")
    private val output by option("--output", "-o", help = "JSONL output; defaults to stdout")
    private val apiKey by option("--api-key", help = "TypeSafe API key; defaults to TYPESAFE_API_KEY")
    private val model by option("--model", help = "Override the recipe model")

    @OptIn(ExperimentalForeignApi::class)
    override fun run() {
        require(input == null || output == null || input != output || input == "-") { "Input and output must differ" }
        val recipePath = resolveRecipePath(recipe, recipeName)
        require(output == null || output != recipePath) { "Output must differ from the recipe" }
        val spec = loadRecipe(recipePath)
        if (input != null && input != "-") {
            val probe = fopen(input!!, "rb") ?: error("Cannot open $input")
            fclose(probe)
        }
        val client = TypeSafeClient(apiKey ?: environmentKey())
        var failures = 0
        try {
            val out = if (output == null || output == "-") stdout else fopen(output!!, "wb") ?: error("Cannot write $output")
            try {
                forEachLine(input ?: "-") { lineNumber, line ->
                    if (line.isBlank()) return@forEachLine
                    var id: JsonElement = JsonPrimitive(lineNumber)
                    val row = try {
                        val item = Json.parseToJsonElement(line).jsonObject
                        val recordId = item.getValue("id")
                        require(recordId is JsonPrimitive && (recordId.isString || recordId.doubleOrNull != null)) {
                            "id must be a string or number"
                        }
                        id = recordId
                        val result = runBlocking { client.evaluate(item.getValue("state"), spec.questions, model ?: spec.model) }
                        buildJsonObject { put("id", id); put("result", result.json) }
                    } catch (e: Exception) {
                        failures++
                        buildJsonObject { put("id", id); put("error", e.message ?: "Evaluation failed") }
                    }
                    writeLine(row.toString(), out)
                }
            } finally {
                if (out != stdout) check(fclose(out) == 0) { "Cannot close $output" }
            }
        } finally {
            client.close()
        }
        check(failures == 0) { "$failures batch item(s) failed" }
    }
}

private class Recipe : CliktCommand(name = "recipe") {
    override fun run() = Unit
}

private class RecipeCheck : CliktCommand(name = "check") {
    private val recipeName by argument("recipe", help = "Recipe name in KLASSIFY_WORKDIR").optional()
    private val recipe by option("--recipe", "-r", help = "JSON recipe path")

    override fun run() {
        val spec = loadRecipe(resolveRecipePath(recipe, recipeName))
        spec.state?.let(::validateState)
        writeLine("OK: ${spec.questions.json.size} question(s), model ${spec.model}")
    }
}

private class Eval : CliktCommand(name = "eval") {
    private val recipeName by argument("recipe", help = "Recipe name in KLASSIFY_WORKDIR").optional()
    private val recipe by option("--recipe", "-r", help = "JSON recipe path")
    private val cases by option("--cases", help = "Labeled JSONL file or - for stdin").required()
    private val threshold by option("--threshold", help = "Noul true cutoff; defaults to 0.5")
    private val apiKey by option("--api-key", help = "TypeSafe API key; defaults to TYPESAFE_API_KEY")
    private val model by option("--model", help = "Override the recipe model")

    override fun run() {
        val cutoff = threshold?.let { it.toDoubleOrNull() ?: error("Invalid threshold: $it") } ?: 0.5
        require(cutoff in 0.0..1.0) { "Threshold must be between 0 and 1" }
        val spec = loadRecipe(resolveRecipePath(recipe, recipeName))
        val metrics = linkedMapOf<String, EvalMetric>()
        spec.questions.json.forEach { (id, value) ->
            metrics[id] = EvalMetric(value.jsonObject.getValue("type").jsonPrimitive.content)
        }
        val client = TypeSafeClient(apiKey ?: environmentKey())
        var count = 0
        try {
            forEachLine(cases) { lineNumber, line ->
                if (line.isBlank()) return@forEachLine
                try {
                    val item = Json.parseToJsonElement(line).jsonObject
                    val expected = item.getValue("expected").jsonObject
                    validateLabels(spec.questions.json, expected)
                    val result = runBlocking { client.evaluate(item.getValue("state"), spec.questions, model ?: spec.model) }
                    recordMetrics(metrics, expected, result.json, cutoff)
                    count++
                } catch (e: Exception) {
                    error("Case line $lineNumber: ${e.message}")
                }
            }
        } finally {
            client.close()
        }
        require(count > 0) { "At least one labeled case is required" }
        writeLine(buildJsonObject {
            put("cases", count)
            put("threshold", cutoff)
            put("questions", buildJsonObject {
                metrics.forEach { (id, metric) ->
                    if (metric.count > 0) put(id, buildJsonObject {
                        put("type", metric.type)
                        put("count", metric.count)
                        if (metric.type == "score") put("mae", metric.error / metric.count)
                        else put("accuracy", metric.correct.toDouble() / metric.count)
                    })
                }
            })
        }.toString())
    }
}

private class Noul : CliktCommand(name = "noul") {
    override val invokeWithoutSubcommand = true
    private val question by option("--question", "-q", help = "One-off yes/no question")
    private val text by option("--text", "-t", help = "Text state for the saved or one-off question")
    private val apiKey by option("--api-key", help = "TypeSafe API key; defaults to TYPESAFE_API_KEY")
    private val model by option("--model", help = "TypeSafe model; defaults to jev-latest")

    override fun run() {
        if (currentContext.invokedSubcommand != null) return
        require(question != null || text != null) { "Pass -q for a one-off question or -t for the saved question" }
        val questionJson = if (question != null) noulQuestions(question!!) else {
            Json.parseToJsonElement(readFile(resolveNoulPath())).jsonObject.getValue("questions").jsonObject
        }
        val questions = QuestionSet.fromJson(questionJson)
        val client = TypeSafeClient(apiKey ?: environmentKey())
        try {
            val state = text?.let(::JsonPrimitive) ?: buildJsonObject { }
            val result = runBlocking { client.evaluate(state, questions, model ?: "jev-latest") }
            writeLine(result.noul("noul_question").probability.toString())
        } finally {
            client.close()
        }
    }
}

private class NoulSet : CliktCommand(name = "set") {
    private val question by option("--question", "-q", help = "Yes/no question to save").required()

    override fun run() {
        val path = resolveNoulPath()
        createDirectories(path.substringBeforeLast('/'))
        writeFile(path, buildJsonObject { put("questions", noulQuestions(question)) }.toString())
        writeLine(path)
    }
}

private fun noulQuestions(question: String): JsonObject {
    require(question.isNotBlank()) { "Question cannot be blank" }
    return buildJsonObject {
        put("noul_question", buildJsonObject {
            put("type", "noul")
            put("instructions", question)
            put("criteria", buildJsonObject {
                put("true", "")
                put("false", "")
            })
        })
    }
}

private class Mcp : CliktCommand(name = "mcp") {
    private val apiKey by option("--api-key", help = "TypeSafe API key; defaults to TYPESAFE_API_KEY")

    override fun run() {
        val client = TypeSafeClient(apiKey ?: environmentKey())
        try {
            while (true) {
                val line = readLine() ?: break
                val request = try {
                    Json.parseToJsonElement(line).jsonObject
                } catch (_: Exception) {
                    writeLine(error(JsonNull, -32700, "Parse error").toString())
                    continue
                }
                val id = request["id"]
                if (id == null) continue // Notifications do not receive responses.
                val response = try {
                    handleMcp(request, client)
                } catch (e: Exception) {
                    error(id, -32602, e.message ?: "Invalid request")
                }
                writeLine(response.toString())
            }
        } finally {
            client.close()
        }
    }
}

private fun handleMcp(request: JsonObject, client: TypeSafeClient): JsonObject {
    val id = request.getValue("id")
    val method = request["method"]?.jsonPrimitive?.content
    val result: JsonElement = when (method) {
        "initialize" -> buildJsonObject {
            put("protocolVersion", "2025-06-18")
            put("capabilities", buildJsonObject { put("tools", buildJsonObject { }) })
            put("serverInfo", buildJsonObject {
                put("name", "klassify")
                put("version", VERSION)
            })
        }
        "ping" -> buildJsonObject { }
        "tools/list" -> buildJsonObject {
            put("tools", buildJsonArray {
                add(buildJsonObject {
                    put("name", "classify")
                    put("description", "Evaluate named Choice, Noul, and Score questions against one state with TypeSafe Jev")
                    put("inputSchema", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("state", buildJsonObject { put("description", "String, object, or array state") })
                            put("questions", buildJsonObject { put("type", "object") })
                            put("model", buildJsonObject { put("type", "string") })
                        })
                        put("required", buildJsonArray { add(JsonPrimitive("state")); add(JsonPrimitive("questions")) })
                    })
                })
            })
        }
        "tools/call" -> {
            val params = request.getValue("params").jsonObject
            require(params["name"]?.jsonPrimitive?.content == "classify") { "Unknown tool" }
            val args = params.getValue("arguments").jsonObject
            val state = args.getValue("state")
            val questions = QuestionSet.fromJson(args.getValue("questions").jsonObject)
            val model = args["model"]?.jsonPrimitive?.content ?: "jev-latest"
            val evaluated = try {
                val response = runBlocking { client.evaluate(state, questions, model) }.json
                buildJsonObject {
                    put("content", buildJsonArray {
                        add(buildJsonObject { put("type", "text"); put("text", response.toString()) })
                    })
                    put("structuredContent", response)
                }
            } catch (e: Exception) {
                buildJsonObject {
                    put("content", buildJsonArray {
                        add(buildJsonObject { put("type", "text"); put("text", e.message ?: "Evaluation failed") })
                    })
                    put("isError", true)
                }
            }
            evaluated
        }
        else -> return error(id, -32601, "Method not found")
    }
    return buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("result", result) }
}

private fun error(id: JsonElement, code: Int, message: String): JsonObject = buildJsonObject {
    put("jsonrpc", "2.0")
    put("id", id)
    put("error", buildJsonObject { put("code", code); put("message", message) })
}

@OptIn(ExperimentalForeignApi::class)
private fun environmentKey(): String = getenv("TYPESAFE_API_KEY")?.toKString()
    ?: error("Set TYPESAFE_API_KEY or pass --api-key")

@OptIn(ExperimentalForeignApi::class)
private fun resolveRecipePath(path: String?, name: String?): String {
    require((path == null) != (name == null)) { "Pass a recipe name or --recipe path, not both" }
    if (path != null) return path
    val workdir = getenv("KLASSIFY_WORKDIR")?.toKString()?.takeIf(String::isNotBlank)
    require(name!!.isNotBlank() && name != "." && name != ".." && '/' !in name && '\\' !in name) {
        "Recipe name must not contain a path"
    }
    val dir = workdir ?: error("Set KLASSIFY_WORKDIR to use a recipe name")
    val file = if (name.endsWith(".json")) name else "$name.json"
    return "${dir.trimEnd('/', '\\')}/$file"
}

@OptIn(ExperimentalNativeApi::class, ExperimentalForeignApi::class)
private fun resolveNoulPath(): String {
    val workdir = getenv("KLASSIFY_WORKDIR")?.toKString()?.takeIf(String::isNotBlank)
    if (workdir != null) return "${workdir.trimEnd('/', '\\')}/noul.json"

    val configDir = when (Platform.osFamily) {
        OsFamily.MACOSX -> "${getenv("HOME")?.toKString() ?: error("Cannot determine the home directory")}/Library/Application Support/klassify"
        OsFamily.LINUX -> {
            val configHome = getenv("XDG_CONFIG_HOME")?.toKString()?.takeIf { it.startsWith('/') }
                ?: "${getenv("HOME")?.toKString() ?: error("Cannot determine the home directory")}/.config"
            "$configHome/klassify"
        }
        OsFamily.WINDOWS -> "${getenv("APPDATA")?.toKString() ?: error("Cannot determine the roaming app data directory")}/klassify"
        else -> error("No default config directory for ${Platform.osFamily}")
    }
    return "$configDir/noul.json"
}

@OptIn(ExperimentalForeignApi::class)
private fun createDirectories(path: String) {
    val normalized = path.replace('\\', '/')
    val parts = normalized.split('/').filter { it.isNotEmpty() && it != "." }
    var current = if (normalized.startsWith('/')) "/" else ""
    parts.forEachIndexed { index, part ->
        if (index == 0 && part.endsWith(':')) {
            current = "$part/"
            return@forEachIndexed
        }
        current = when {
            current.isEmpty() -> part
            current.endsWith('/') -> "$current$part"
            else -> "$current/$part"
        }
        if (mkdir(current, 448.convert()) != 0) {
            check(access(current, F_OK) == 0) { "Cannot create directory $current" }
        }
    }
}

private data class RecipeSpec(val questions: QuestionSet, val model: String, val state: JsonElement?)

private fun loadRecipe(path: String): RecipeSpec {
    val json = Json.parseToJsonElement(readFile(path)).jsonObject
    val questions = QuestionSet.fromJson(json.getValue("questions").jsonObject)
    val modelValue = json["model"]
    require(modelValue == null || modelValue is JsonPrimitive && modelValue.isString) { "Model must be a string" }
    val model = modelValue?.jsonPrimitive?.content ?: "jev-latest"
    require(model.isNotBlank()) { "Model cannot be blank" }
    return RecipeSpec(questions, model, json["state"])
}

private fun validateState(state: JsonElement) {
    require(state is JsonObject || state is JsonArray ||
        state is JsonPrimitive && state.isString && state.content.isNotBlank()) {
        "Recipe state must be a nonblank string, object, or array"
    }
}

internal fun selectField(value: JsonElement, path: String): JsonElement {
    require(path.isNotBlank() && path.split('.').all(String::isNotBlank)) { "Invalid field path" }
    return path.split('.').fold(value) { current, key ->
        (current as? JsonObject)?.get(key) ?: error("Missing field '$path'")
    }
}

internal class EvalMetric(val type: String, var count: Int = 0, var correct: Int = 0, var error: Double = 0.0)

internal fun validateLabels(questions: JsonObject, expected: JsonObject) {
    require(expected.isNotEmpty()) { "Expected labels cannot be empty" }
    expected.forEach { (id, label) ->
        val question = questions[id]?.jsonObject ?: error("Unknown expected question '$id'")
        when (question.getValue("type").jsonPrimitive.content) {
            "choice" -> require(label is JsonPrimitive && label.isString &&
                label.content in question.getValue("criteria").jsonObject) { "Invalid choice label '$id'" }
            "noul" -> require(label is JsonPrimitive && !label.isString && label.booleanOrNull != null) {
                "Noul label '$id' must be boolean"
            }
            "score" -> {
                val value = (label as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.doubleOrNull
                val highestLevel = question.getValue("criteria").jsonArray.lastIndex.toDouble()
                require(value != null && value in 0.0..highestLevel) {
                    "Score label '$id' must be between 0 and $highestLevel"
                }
            }
        }
    }
}

internal fun recordMetrics(
    metrics: Map<String, EvalMetric>, expected: JsonObject, result: JsonObject, threshold: Double,
) {
    val answers = result.getValue("answers").jsonObject
    expected.forEach { (id, label) ->
        val metric = metrics.getValue(id)
        val answer = answers.getValue(id).jsonObject
        when (metric.type) {
            "choice" -> if (answer.getValue("choice").jsonPrimitive.content == label.jsonPrimitive.content) metric.correct++
            "noul" -> if ((answer.getValue("noul").jsonPrimitive.doubleOrNull!! >= threshold) ==
                label.jsonPrimitive.booleanOrNull) metric.correct++
            "score" -> metric.error += kotlin.math.abs(answer.getValue("score").jsonPrimitive.doubleOrNull!! -
                label.jsonPrimitive.doubleOrNull!!)
        }
        metric.count++
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun forEachLine(path: String, block: (Int, String) -> Unit) {
    val file = if (path == "-") stdin else fopen(path, "rb") ?: error("Cannot open $path")
    try {
        var lineNumber = 0
        while (true) {
            val line = readLine(file) ?: break
            block(++lineNumber, line)
        }
        check(ferror(file) == 0) { "Cannot read $path" }
    } finally {
        if (file != stdin) fclose(file)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun readFile(path: String): String {
    val file = fopen(path, "rb") ?: error("Cannot open $path")
    try { return readAll(file) } finally { fclose(file) }
}

@OptIn(ExperimentalForeignApi::class)
private fun writeFile(path: String, contents: String) {
    val file = fopen(path, "wb") ?: error("Cannot write $path")
    val written = fputs(contents, file)
    val closed = fclose(file)
    check(written != EOF && closed == 0) { "Cannot write $path" }
}

@OptIn(ExperimentalForeignApi::class)
private fun readAll(file: CPointer<FILE>?): String {
    val chunks = mutableListOf<ByteArray>()
    val buffer = ByteArray(8192)
    while (true) {
        val count = buffer.usePinned { fread(it.addressOf(0), 1.convert(), buffer.size.convert(), file) }.toInt()
        if (count == 0) break
        chunks += buffer.copyOf(count)
    }
    return ByteArray(chunks.sumOf { it.size }).also { bytes ->
        var offset = 0
        chunks.forEach { chunk -> chunk.copyInto(bytes, offset); offset += chunk.size }
    }.decodeToString()
}

@OptIn(ExperimentalForeignApi::class)
private fun readLine(): String? = readLine(stdin)

@OptIn(ExperimentalForeignApi::class)
private fun readLine(file: CPointer<FILE>?): String? {
    val bytes = mutableListOf<Byte>()
    while (true) {
        val next = fgetc(file)
        if (next == EOF) return if (bytes.isEmpty()) null else bytes.toByteArray().decodeToString()
        if (next == '\n'.code) return bytes.toByteArray().decodeToString()
        bytes += next.toByte()
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun writeLine(text: String) = writeLine(text, stdout)

@OptIn(ExperimentalForeignApi::class)
private fun writeLine(text: String, file: CPointer<FILE>?) {
    check(fputs(text + "\n", file) != EOF && fflush(file) == 0) { "Cannot write output" }
}

fun main(args: Array<String>) {
    try {
        Klassify().subcommands(Run(), Batch(), Eval(), Recipe().subcommands(RecipeCheck()), Noul().subcommands(NoulSet()), Mcp()).main(args)
    } catch (e: Exception) {
        Terminal().danger(e.message ?: "Klassify failed", stderr = true)
        exitProcess(1)
    }
}
