package com.fajarnuha.klassify.cli

import com.fajarnuha.klassify.QuestionSet
import com.fajarnuha.klassify.TypeSafeClient
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import platform.posix.FILE
import platform.posix.EOF
import platform.posix.fclose
import platform.posix.fflush
import platform.posix.fgetc
import platform.posix.fopen
import platform.posix.fputs
import platform.posix.fread
import platform.posix.getenv
import platform.posix.stdin
import platform.posix.stdout
import kotlin.system.exitProcess

private const val VERSION = "0.1.2"

private class Klassify : CliktCommand(name = "klassify") {
    override fun run() = Unit
}

private class Run : CliktCommand(name = "run") {
    private val recipe by option("--recipe", "-r", help = "JSON recipe file").required()
    private val text by option("--text", "-t", help = "Text state; defaults to recipe state or stdin")
    private val stateJson by option("--state-json", "-j", help = "JSON state file")
    private val apiKey by option("--api-key", help = "TypeSafe API key; defaults to TYPESAFE_API_KEY")
    private val model by option("--model", help = "Override the recipe model")

    @OptIn(ExperimentalForeignApi::class)
    override fun run() {
        require(text == null || stateJson == null) { "Use either --text or --state-json" }
        val recipeObject = Json.parseToJsonElement(readFile(recipe)).jsonObject
        val questions = QuestionSet.fromJson(recipeObject.getValue("questions").jsonObject)
        val state: JsonElement = when {
            text != null -> JsonPrimitive(text!!)
            stateJson != null -> Json.parseToJsonElement(readFile(stateJson!!))
            recipeObject["state"] != null -> recipeObject.getValue("state")
            else -> JsonPrimitive(readAll(stdin))
        }
        val selectedModel = model ?: recipeObject["model"]?.jsonPrimitive?.content ?: "jev-latest"
        val client = TypeSafeClient(apiKey ?: environmentKey())
        try {
            val result = runBlocking { client.evaluate(state, questions, selectedModel) }
            writeLine(result.json.toString())
        } finally {
            client.close()
        }
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
private fun readFile(path: String): String {
    val file = fopen(path, "rb") ?: error("Cannot open $path")
    try { return readAll(file) } finally { fclose(file) }
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
private fun readLine(): String? {
    val bytes = mutableListOf<Byte>()
    while (true) {
        val next = fgetc(stdin)
        if (next == EOF) return if (bytes.isEmpty()) null else bytes.toByteArray().decodeToString()
        if (next == '\n'.code) return bytes.toByteArray().decodeToString()
        bytes += next.toByte()
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun writeLine(text: String) {
    fputs(text + "\n", stdout)
    fflush(stdout)
}

fun main(args: Array<String>) {
    try {
        Klassify().subcommands(Run(), Mcp()).main(args)
    } catch (e: Exception) {
        Terminal().danger(e.message ?: "Klassify failed", stderr = true)
        exitProcess(1)
    }
}
