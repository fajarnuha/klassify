package com.fajarnuha.klassify.recipe

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    try {
        when (args[0]) {
            "check" -> {
                val probe = RecipeSource(
                    "import com.fajarnuha.klassify.recipe.recipe\nrecipe(args) {}",
                    questions = null,
                )
                prepare(probe, emptyList())
                println("ok")
            }
            "install" -> {
                val pack = installPack(Path.of(args[1]), args[2])
                println("Installed ${pack.summary()}")
            }
            "list" -> {
                val packs = listPacks(Path.of(args[1]))
                if (packs.isEmpty()) println("No recipe packs installed.")
                else packs.forEach { println(it.summary()) }
            }
            "update" -> {
                val (pack, previousVersion) = updatePack(Path.of(args[1]), args[2], args.getOrNull(3))
                if (pack.version == previousVersion) println("${pack.id} ${pack.version} is already up to date.")
                else println("Updated ${pack.id} from $previousVersion to ${pack.version} (${pack.recipes.keys.joinToString()})")
            }
            "uninstall" -> {
                val id = args[2]
                uninstallPack(Path.of(args[1]), id)
                println("Uninstalled $id")
            }
            "prepare" -> writeJson(prepare(findRecipe(Path.of(args[1]), args[2]), args.drop(3)))
            "postprocess" -> {
                val request = readJson()
                val source = findRecipe(Path.of(args[1]), args[2])
                require(request.string("sourceFingerprint") == source.fingerprint()) {
                    "The recipe changed during classification. Run it again before applying moves."
                }
                val ids = request.getValue("results").jsonArray.map { it.jsonObject.string("id") }.toSet()
                writeJson(planFileMoves(runScript(source, request), ids))
            }
            "apply" -> println(applyFileMoves(readJson()))
            else -> error("Unknown recipe runner command")
        }
    } catch (e: Exception) {
        System.err.println(e.message ?: "Recipe runner failed")
        exitProcess(1)
    }
}

private fun Pack.summary(): String = "$id $version (${recipes.keys.joinToString()})"

private fun readJson(): JsonObject =
    Json.parseToJsonElement(System.`in`.readBytes().decodeToString()).jsonObject

private fun writeJson(value: JsonObject) {
    System.out.write(value.toString().encodeToByteArray())
}

private fun RecipeSource.fingerprint(): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(script.encodeToByteArray())
    return java.util.HexFormat.of().formatHex(bytes)
}

private fun prepare(source: RecipeSource, options: List<String>): JsonObject {
    val prepared = runScript(source, buildJsonObject {
        put("options", JsonArray(options.map(::JsonPrimitive)))
        source.questions?.let { put("questions", it) }
        source.sampleState?.let { put("sampleState", it) }
    })
    if (prepared["postprocess"]?.jsonPrimitive?.booleanOrNull != true) return prepared
    return JsonObject(prepared + ("sourceFingerprint" to JsonPrimitive(source.fingerprint())))
}

private fun runScript(source: RecipeSource, input: JsonObject): JsonObject {
    val directory = Files.createTempDirectory("klassify-recipe-")
    val script = directory.resolve("recipe.kts")
    val request = directory.resolve("request.json")
    val response = directory.resolve("response.json")
    try {
        Files.writeString(script, source.script)
        Files.writeString(request, input.toString())
        val stdout = System.out
        val result = try {
            System.setOut(System.err)
            // The embedded compiler initializes IntelliJ's platform classes.
            System.setProperty("idea.home.path", directory.toString())
            K2JVMCompiler().exec(
                System.err,
                "-no-stdlib", "-no-reflect",
                "-classpath", System.getProperty("java.class.path"),
                "-script", script.toString(), "--", request.toString(), response.toString(),
            )
        } finally {
            System.setOut(stdout)
        }
        check(result == ExitCode.OK) { "Recipe script failed" }
        check(Files.isRegularFile(response)) { "Recipe script did not call recipe(args)" }
        return Json.parseToJsonElement(Files.readString(response)).jsonObject
    } finally {
        Files.deleteIfExists(response)
        Files.deleteIfExists(request)
        Files.deleteIfExists(script)
        Files.deleteIfExists(directory)
    }
}
