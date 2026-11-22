package com.fajarnuha.klassify.cli

import com.fajarnuha.klassify.TypeSafeClient
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.mordant.terminal.Terminal
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.OsFamily
import kotlin.native.Platform
import platform.posix.F_OK
import platform.posix.access
import platform.posix.getenv

internal class RecipeInstall : CliktCommand(name = "install") {
    private val source by argument(help = "Official pack ID, local ZIP path, or HTTPS URL")

    override fun help(context: Context): String = "Install a Kotlin recipe pack"

    override fun run() {
        writeLine(callRunner("install", source).trimEnd())
    }
}

internal class RecipeList : CliktCommand(name = "list") {
    override fun help(context: Context): String = "List installed recipe packs and their recipes"

    override fun run() {
        writeLine(callRunner("list").trimEnd())
    }
}

internal class RecipeUpdate : CliktCommand(name = "update") {
    private val packId by argument("pack-id", help = "Pack ID shown by klassify recipe list")
    private val source by argument(help = "Override the pack’s update URL with an HTTPS URL or local ZIP path").optional()

    override fun help(context: Context): String = "Update an installed recipe pack"

    override fun run() {
        writeLine(callRunner("update", *listOfNotNull(packId, source).toTypedArray()).trimEnd())
    }
}

internal class RecipeUninstall : CliktCommand(name = "uninstall") {
    private val packId by argument("pack-id", help = "Pack ID shown by klassify recipe list")

    override fun help(context: Context): String = "Uninstall a recipe pack by ID"

    override fun run() {
        writeLine(callRunner("uninstall", packId).trimEnd())
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun environment(name: String): String? = getenv(name)?.toKString()?.takeIf(String::isNotBlank)

private fun packDirectory(): String =
    environment("KLASSIFY_RECIPE_DIR") ?: "${configDirectory()}/recipe-packs"

@OptIn(ExperimentalForeignApi::class)
internal fun hasInstalledRecipeCandidate(name: String): Boolean {
    if (!name.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)*"))) return false
    val workdir = environment("KLASSIFY_WORKDIR")
    if (workdir != null && access("${workdir.trimEnd('/', '\\')}/$name.json", F_OK) == 0) return false
    return runCatching { access(packDirectory(), F_OK) == 0 }.getOrDefault(false)
}

internal class RecipeDoctor : CliktCommand(name = "doctor") {
    override fun help(context: Context): String = "Check the bundled Kotlin runner and Java"

    override fun run() {
        val runner = recipeRunner()
        val output = runCatching { captureRunner(runner.launch + "check", mergeError = true) }.getOrNull()
        check(output?.trimEnd()?.lineSequence()?.lastOrNull() == "ok") {
            "The bundled Kotlin recipe runner could not start with Java ${runner.javaVersion}. Reinstall Klassify and try again with a Java 21 JDK."
        }
        writeLine("Kotlin recipe runner ready (Java ${runner.javaVersion})")
    }
}

private data class Runner(val launch: List<String>, val javaVersion: Int)

@OptIn(ExperimentalForeignApi::class)
private fun recipeRunner(): Runner {
    val directory = executablePath().replace('\\', '/').substringBeforeLast('/')
    val installed = "$directory/klassify-recipe-runner/$VERSION/lib"
    val packaged = "$directory/../libexec/klassify-recipe-runner/$VERSION/lib"
    val lib = listOf(installed, packaged).firstOrNull { access(it, F_OK) == 0 }
        ?: error("The bundled Kotlin recipe runner is missing. Reinstall Klassify or extract the full CLI archive.")
    val detected = mutableListOf<Int>()
    for (java in javaCandidates()) {
        val output = runCatching { captureRunner(listOf(java, "-XshowSettings:properties", "-version"), mergeError = true) }
            .getOrNull() ?: continue
        val version = javaMajorVersion(output) ?: continue
        if (version >= 21) {
            return Runner(listOf(java, "-cp", "$lib/*", "com.fajarnuha.klassify.recipe.RunnerKt"), version)
        }
        detected += version
    }
    val reason = detected.maxOrNull()?.let { "Found Java $it." } ?: "No working Java installation was found."
    error("$reason Kotlin recipes require Java 21 or newer. Install a Java 21+ JDK, then set JAVA_HOME or put its bin directory on PATH. Run 'klassify recipe doctor' to verify.")
}

@OptIn(ExperimentalNativeApi::class, ExperimentalForeignApi::class)
private fun javaCandidates(): List<String> {
    val windows = Platform.osFamily == OsFamily.WINDOWS
    val path = environment("PATH").orEmpty().split(if (windows) ';' else ':')
    val javaHome = environment("JAVA_HOME")?.let { "${it.trimEnd('/', '\\')}/bin" }
    // Resolve Java from absolute paths so recipe input directories do not affect executable lookup.
    return (listOfNotNull(javaHome) + path).mapNotNull { entry ->
        val directory = if (windows) entry.removeSurrounding("\"").replace('\\', '/') else entry
        val absolute = if (windows) {
            directory.startsWith("//") || Regex("^[A-Za-z]:/").containsMatchIn(directory)
        } else {
            directory.startsWith('/')
        }
        val java = "${directory.trimEnd('/')}/java"
        val executable = if (windows) "$java.exe" else java
        java.takeIf { absolute && access(executable, F_OK) == 0 }
    }.distinct()
}

private fun javaMajorVersion(settings: String): Int? =
    Regex("""(?m)^\s*java\.specification\.version\s*=\s*(?:1\.)?(\d+)\s*$""")
        .find(settings)?.groupValues?.get(1)?.toIntOrNull()

private fun callRunner(command: String, vararg arguments: String, input: JsonObject? = null): String =
    captureRunner(recipeRunner().launch + listOf(command, packDirectory()) + arguments, input = input?.toString())

internal expect fun executablePath(): String
internal expect fun captureRunner(arguments: List<String>, mergeError: Boolean = false, input: String? = null): String

internal data class RecipeItem(val id: JsonElement, val state: JsonElement)
internal data class PreparedRecipe(val json: JsonObject, val spec: RecipeSpec, val items: List<RecipeItem>)

internal fun prepareRecipe(name: String, options: List<String>, model: String?): PreparedRecipe {
    require(model == null || model.isNotBlank()) { "Model cannot be blank" }
    val prepared = Json.parseToJsonElement(callRunner("prepare", name, *options.toTypedArray())).jsonObject
    val json = if (model == null) prepared else JsonObject(prepared + ("model" to JsonPrimitive(model)))
    val spec = parseRecipe(json)
    val ids = mutableSetOf<String>()
    val items = json.getValue("items").jsonArray.map { value ->
        val item = value.jsonObject
        val id = item.getValue("id").jsonPrimitive
        require(id.isString && id.content.isNotBlank() && ids.add(id.content)) {
            "Prepared items must have unique, nonblank string IDs"
        }
        val state = item.getValue("state")
        validateState(state)
        RecipeItem(id, state)
    }
    return PreparedRecipe(json, spec, items)
}

internal fun evaluateBatchItems(
    items: List<RecipeItem>, spec: RecipeSpec, field: String?, client: TypeSafeClient, collectResults: Boolean,
): List<JsonObject> {
    val results = mutableListOf<JsonObject>()
    var failures = 0
    for ((id, state) in items) {
        val row = try {
            val result = runBlocking { client.evaluate(state, spec.questions, spec.model) }.json
            val row = classificationRow(id, result, field)
            if (collectResults) results.add(if (field == null) row else classificationRow(id, result, null))
            row
        } catch (e: Exception) {
            failures++
            buildJsonObject {
                put("id", id)
                put("error", e.message ?: "Evaluation failed")
            }
        }
        writeLine(row.toString())
    }
    check(failures == 0) { "$failures recipe item(s) failed" }
    return results
}

internal fun postprocessRecipe(
    name: String, options: List<String>, prepared: PreparedRecipe, results: List<JsonObject>, terminal: Terminal,
) {
    val plan = if (results.isEmpty()) buildJsonObject { put("moves", JsonArray(emptyList())) } else {
        val request = buildJsonObject {
            put("sourceFingerprint", prepared.json.getValue("sourceFingerprint"))
            put("options", JsonArray(options.map(::JsonPrimitive)))
            put("results", JsonArray(results))
        }
        Json.parseToJsonElement(callRunner("postprocess", name, input = request)).jsonObject
    }
    confirmFileMoves(plan, terminal) { callRunner("apply", input = plan).trim().toInt() }
}

internal fun confirmFileMoves(plan: JsonObject, terminal: Terminal, apply: () -> Int) {
    val moves = plan.getValue("moves").jsonArray
    if (moves.isEmpty()) {
        terminal.println("No file moves proposed.", stderr = true)
        return
    }
    terminal.println("Proposed moves in ${plan.getValue("directory")}", stderr = true)
    for (move in moves) {
        val item = move.jsonObject
        val id = item.getValue("id").jsonPrimitive.content
        val folder = item.getValue("folder").jsonPrimitive.content
        terminal.println("${JsonPrimitive(id)} → ${JsonPrimitive("$folder/$id")}", stderr = true)
    }
    val noun = if (moves.size == 1) "file" else "files"
    terminal.rawPrint("Move ${moves.size} $noun? [y/N] ", stderr = true)
    val answer = terminal.readLineOrNull(hideInput = false)?.trim()?.lowercase()
    if (answer != "y" && answer != "yes") {
        terminal.println("No files moved.", stderr = true)
        return
    }
    val moved = apply()
    terminal.println("Moved $moved $noun.", stderr = true)
}
