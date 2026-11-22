package com.fajarnuha.klassify.recipe

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.time.Duration
import java.util.zip.ZipFile
import kotlin.io.path.extension

private const val MAX_PACK_BYTES = 16 * 1024 * 1024
private const val MAX_ENTRY_BYTES = 1024 * 1024
private val identifier = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")
private val entryPart = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")
private val officialAssets = mapOf(
    "klassify-starter" to "klassify-starter-recipes.zip",
    "android-dev" to "klassify-android-dev-recipes.zip",
)

internal data class Pack(
    val id: String,
    val version: String,
    val recipes: Map<String, String>,
    val updateUrl: String?,
)

internal fun installPack(root: Path, source: String): Pack {
    val url = officialPackUrl(source)
    if (url != null) return savePack(root, url, expectedId = source).pack
    require(!identifier.matches(source) || Files.isRegularFile(Path.of(source))) {
        "Unknown official pack '$source'. Choose ${officialAssets.keys.joinToString(" or ")}, or pass a ZIP path or HTTPS URL."
    }
    return savePack(root, source).pack
}

internal data class PackChange(val pack: Pack, val previousVersion: String?)

internal fun updatePack(root: Path, id: String, source: String?): PackChange {
    require(identifier.matches(id)) { "Invalid pack ID '$id'" }
    val archive = root.resolve("$id.zip")
    require(Files.isRegularFile(archive)) { missingPack(id) }
    if (source != null) return savePack(root, source, expectedId = id, requireInstalled = true)

    val url = withPackLock(root, shared = true) {
        require(Files.isRegularFile(archive)) { missingPack(id) }
        val pack = readPack(archive)
        require(pack.id == id) { missingPack(id) }
        pack.automaticUpdateUrl()
            ?: error("Pack '$id' has no update URL. Run 'klassify recipe update $id <source>' with a ZIP path or HTTPS URL.")
    }
    return savePack(root, url, expectedId = id, requireInstalled = true, expectedUpdateUrl = url)
}

private fun Pack.automaticUpdateUrl(): String? = officialPackUrl(id) ?: updateUrl

private fun officialPackUrl(id: String): String? =
    officialAssets[id]?.let { "https://github.com/fajarnuha/klassify/releases/latest/download/$it" }

private fun savePack(
    root: Path,
    source: String,
    expectedId: String? = null,
    requireInstalled: Boolean = false,
    expectedUpdateUrl: String? = null,
): PackChange {
    Files.createDirectories(root)
    val incoming = Files.createTempFile(root, ".incoming-", ".tmp")
    try {
        val bytes = if (source.startsWith("https://")) {
            val client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()
            val request = HttpRequest.newBuilder(URI(source)).timeout(Duration.ofSeconds(60)).build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
            response.body().use {
                require(response.statusCode() in 200..299) { "Pack download returned HTTP ${response.statusCode()}" }
                it.readNBytes(MAX_PACK_BYTES + 1)
            }
        } else {
            require("://" !in source) { "Use an HTTPS URL or a local ZIP file" }
            Files.newInputStream(Path.of(source)).use { it.readNBytes(MAX_PACK_BYTES + 1) }
        }
        require(bytes.size <= MAX_PACK_BYTES) { "Recipe pack exceeds 16 MiB" }
        Files.write(incoming, bytes)
        val pack = readPack(incoming)
        require(expectedId == null || pack.id == expectedId) {
            "Expected pack '$expectedId', but the archive contains '${pack.id}'"
        }
        return withPackLock(root) {
            val destination = root.resolve("${pack.id}.zip")
            val installed = if (Files.isRegularFile(destination)) readPack(destination) else null
            val change = PackChange(pack, installed?.version)
            if (requireInstalled) require(installed?.id == pack.id) { missingPack(pack.id) }
            require(expectedUpdateUrl == null || installed?.automaticUpdateUrl() == expectedUpdateUrl) {
                "The update URL for pack '${pack.id}' changed during the download. Run the update again."
            }
            if (installed != null && pack.version == installed.version) {
                require(Files.mismatch(destination, incoming) == -1L) {
                    "Pack ${pack.id} ${pack.version} is already installed with different contents"
                }
                return@withPackLock change
            }
            if (expectedId != null && installed != null) {
                require(comparePackVersions(pack.version, installed.version) > 0) {
                    "Pack ${pack.id} ${pack.version} must be newer than installed version ${installed.version}"
                }
            }
            for (path in installedPacks(root).filter { it != destination }) {
                val other = readPack(path)
                val conflict = pack.recipes.keys.intersect(other.recipes.keys).firstOrNull()
                require(conflict == null) { "Recipe '$conflict' already belongs to pack ${other.id}" }
            }
            Files.move(incoming, destination, ATOMIC_MOVE, REPLACE_EXISTING)
            change
        }
    } finally {
        Files.deleteIfExists(incoming)
    }
}

internal fun listPacks(root: Path): List<Pack> =
    if (Files.isDirectory(root)) withPackLock(root, shared = true) { installedPacks(root).map(::readPack) }
    else emptyList()

internal fun uninstallPack(root: Path, id: String) {
    require(identifier.matches(id)) { "Invalid pack ID '$id'" }
    val missing = missingPack(id)
    require(Files.isDirectory(root)) { missing }
    withPackLock(root) {
        val archive = root.resolve("$id.zip")
        require(Files.isRegularFile(archive) && Files.deleteIfExists(archive)) { missing }
    }
}

private fun missingPack(id: String): String =
    "Recipe pack '$id' is not installed. Run 'klassify recipe list' to see installed pack IDs."

internal data class RecipeSource(
    val script: String,
    val questions: JsonObject?,
    val sampleState: JsonElement? = null,
)

internal fun findRecipe(root: Path, name: String): RecipeSource {
    val missing = "Installed recipe '$name' was not found"
    require(Files.isDirectory(root)) { missing }
    return withPackLock(root, shared = true) {
        for (path in installedPacks(root)) {
            ZipFile(path.toFile()).use { zip ->
                val entry = readPack(zip).recipes[name] ?: return@use
                val companion = entry.removeSuffix(".recipe.kts") + ".json"
                val template = zip.getEntry(companion)?.let {
                    Json.parseToJsonElement(zip.readText(companion)).jsonObject
                }
                return@withPackLock RecipeSource(
                    zip.readText(entry), template?.getValue("questions")?.jsonObject, template?.get("state"),
                )
            }
        }
        error(missing)
    }
}

private fun <T> withPackLock(root: Path, shared: Boolean = false, block: () -> T): T {
    val lock = root.resolve(".install.lock")
    if (shared && Files.notExists(lock)) return block()
    val options = if (shared) setOf(READ) else setOf(CREATE, WRITE)
    return FileChannel.open(lock, options).use { channel ->
        channel.lock(0, Long.MAX_VALUE, shared).use { block() }
    }
}

private fun installedPacks(root: Path): List<Path> {
    if (!Files.isDirectory(root)) return emptyList()
    return Files.list(root).use { files ->
        files.filter { it.extension == "zip" && Files.isRegularFile(it) }.sorted().toList()
    }
}

private fun readPack(path: Path): Pack = ZipFile(path.toFile()).use(::readPack)

private fun readPack(zip: ZipFile): Pack {
    require(zip.size() in 1..128) { "Recipe pack must contain between 1 and 128 entries" }
    val names = mutableSetOf<String>()
    zip.entries().asSequence().forEach { entry ->
        val name = entry.name.removeSuffix("/")
        require(name.split('/').all(entryPart::matches) && names.add(name)) {
            "Invalid or duplicate pack entry '${entry.name}'"
        }
        require(entry.size in 0..MAX_ENTRY_BYTES.toLong()) { "Pack entry '${entry.name}' exceeds 1 MiB" }
    }
    val manifest = Json.parseToJsonElement(zip.readText("pack.json")).jsonObject
    require(manifest.getValue("schemaVersion").jsonPrimitive.int == 1) { "Unsupported pack schema version" }
    val id = manifest.string("id")
    require(identifier.matches(id)) { "Invalid pack ID '$id'" }
    val version = manifest.string("version")
    require(version.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[A-Za-z0-9.-]+)?"))) { "Invalid pack version '$version'" }
    val updateUrl = manifest["updateUrl"]?.let {
        val url = manifest.string("updateUrl")
        val uri = runCatching { URI(url) }.getOrNull()
        require(uri?.scheme == "https" && uri.host != null && uri.userInfo == null && uri.fragment == null) {
            "'updateUrl' must be an absolute HTTPS URL without credentials or a fragment"
        }
        url
    }
    val recipes = linkedMapOf<String, String>()
    manifest.getValue("recipes").jsonArray.forEach { element ->
        val recipe = element.jsonObject
        val name = recipe.string("name")
        val entrypoint = recipe.string("entrypoint")
        require(identifier.matches(name) && name !in recipes) { "Invalid or duplicate recipe name '$name'" }
        require(recipe.string("runtime") == "kotlin-jvm") { "Unsupported runtime for '$name'" }
        require(recipe.getValue("runnerApi").jsonPrimitive.int in 1..2) {
            "Unsupported runner API for '$name'. Check that Klassify is up to date."
        }
        require(entrypoint in names && entrypoint.endsWith(".recipe.kts") && zip.getEntry(entrypoint)?.isDirectory == false) {
            "Missing .recipe.kts entry point for '$name'"
        }
        recipes[name] = entrypoint
    }
    require(recipes.isNotEmpty()) { "Recipe pack is empty" }
    return Pack(id, version, recipes, updateUrl)
}

internal fun ZipFile.readText(name: String): String {
    val entry = getEntry(name)?.takeUnless { it.isDirectory } ?: error("Pack file '$name' was not found")
    val bytes = getInputStream(entry).use { it.readNBytes(MAX_ENTRY_BYTES + 1) }
    require(bytes.size <= MAX_ENTRY_BYTES) { "Pack entry '$name' exceeds 1 MiB" }
    return bytes.decodeToString(throwOnInvalidSequence = true)
}

internal fun JsonObject.string(key: String): String {
    val value = getValue(key).jsonPrimitive
    require(value.isString && value.content.isNotBlank()) { "'$key' must be a nonblank string" }
    return value.content
}
