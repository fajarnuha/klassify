package com.fajarnuha.klassify.recipe

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

internal fun planFileMoves(proposal: JsonObject, itemIds: Set<String>): JsonObject {
    if (proposal.isEmpty()) return buildJsonObject { put("moves", JsonArray(emptyList())) }
    val directory = Path.of(proposal.string("directory")).toRealPath()
    require(Files.isDirectory(directory, NOFOLLOW_LINKS)) { "Not a directory: $directory" }
    val folders = proposal.getValue("folders").jsonArray.map { it.jsonPrimitive.content }.toSet()
    val destinations = proposal.getValue("destinations").jsonObject
    val moves = destinations.keys.map { id ->
        val folder = destinations.string(id)
        require(id in itemIds) { "Move refers to an unclassified item '$id'" }
        require(folder in folders) { "Folder '$folder' was not one of the requested folders" }
        val (source, target) = movePaths(directory, id, folder)
        buildJsonObject {
            put("id", id)
            put("folder", folder)
            put("sourceSnapshot", sourceSnapshot(source))
            checkTarget(target)
        }
    }
    return buildJsonObject {
        put("directory", directory.toString())
        put("moves", JsonArray(moves))
    }
}

internal fun applyFileMoves(plan: JsonObject): Int {
    val directory = Path.of(plan.string("directory"))
    require(directory.isAbsolute && directory.toRealPath() == directory && Files.isDirectory(directory, NOFOLLOW_LINKS)) {
        "The sorting directory changed after preview"
    }
    val seen = mutableSetOf<Path>()
    val moves = plan.getValue("moves").jsonArray.map { entry ->
        val move = entry.jsonObject
        val (source, target) = movePaths(directory, move.string("id"), move.string("folder"))
        require(seen.add(source)) { "Duplicate file in the move plan" }
        PlannedFileMove(source, target, move.getValue("sourceSnapshot").jsonObject).also { it.validate() }
    }

    var moved = 0
    for (move in moves) {
        try {
            move.validate()
            if (Files.notExists(move.target.parent, NOFOLLOW_LINKS)) Files.createDirectory(move.target.parent)
            checkTarget(move.target)
            Files.move(move.source, move.target)
            moved++
        } catch (e: Exception) {
            error("Moved $moved of ${moves.size} files. Could not move '${move.source.fileName}': ${e.message}")
        }
    }
    return moved
}

private data class PlannedFileMove(val source: Path, val target: Path, val snapshot: JsonObject) {
    fun validate() {
        check(sourceSnapshot(source) == snapshot) { "Source changed after preview: $source" }
        checkTarget(target)
    }
}

private fun movePaths(directory: Path, id: String, folder: String): Pair<Path, Path> {
    require(listOf(id, folder).all {
        it.isNotBlank() && it != "." && it != ".." && '/' !in it && '\\' !in it &&
            !Path.of(it).isAbsolute && Path.of(it).fileName.toString() == it
    }) { "Move must use a filename and a single destination folder name" }
    return directory.resolve(id) to directory.resolve(folder).resolve(id)
}

private fun sourceSnapshot(source: Path): JsonObject {
    val attributes = Files.readAttributes(source, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    check(attributes.isRegularFile) { "Source is not a regular file: $source" }
    return buildJsonObject {
        put("size", attributes.size())
        put("modified", attributes.lastModifiedTime().toString())
        put("created", attributes.creationTime().toString())
        attributes.fileKey()?.let { put("fileKey", it.toString()) }
    }
}

private fun checkTarget(target: Path) {
    val parent = target.parent
    check(Files.notExists(parent, NOFOLLOW_LINKS) ||
        (Files.isDirectory(parent, NOFOLLOW_LINKS) && parent.toRealPath() == parent)) {
        "Destination folder is not a regular directory: $parent"
    }
    check(Files.notExists(target, NOFOLLOW_LINKS)) { "Destination already exists or is inaccessible: $target" }
}
