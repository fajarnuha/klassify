package com.fajarnuha.klassify

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid

/**
 * Encodes an image for use as an item in a JSON state array, alongside any text.
 * PNG and JPEG are supported across all providers when using an image-capable model.
 */
public fun imageInput(bytes: ByteArray, mimeType: String): JsonObject {
    require(bytes.isNotEmpty()) { "Image bytes cannot be empty" }
    require(imageMimeType.matches(mimeType)) { "Expected an image MIME type, such as image/png" }
    return buildJsonObject {
        put("type", "image_url")
        put("image_url", buildJsonObject {
            put("url", "data:$mimeType;base64,${Base64.encode(bytes)}")
        })
    }
}

private val imageMimeType = Regex("image/[a-z0-9][a-z0-9.+-]*")

internal fun JsonElement.imageUrl(): String? {
    if (this !is JsonObject || get("type") != JsonPrimitive("image_url")) return null
    val url = (get("image_url") as? JsonObject)?.get("url") as? JsonPrimitive
    require(url != null && url.isString && url.content.isNotBlank()) { "Image input needs an image_url.url string" }
    return url.content
}

internal fun String.base64Image(): Pair<String, String> {
    val mimeType = substringAfter("data:", "").substringBefore(';')
    val prefix = "data:$mimeType;base64,"
    require(imageMimeType.matches(mimeType) && startsWith(prefix) && length > prefix.length) {
        "Expected a base64 image data URL"
    }
    return mimeType to removePrefix(prefix)
}

internal fun typeSafeImageReferences(
    state: JsonElement,
    assets: MutableMap<String, JsonElement>,
): JsonElement {
    val url = state.imageUrl()
    if (url != null) {
        val (mimeType, data) = url.base64Image()
        require(mimeType == "image/png" || mimeType == "image/jpeg") { "TypeSafe Jev accepts PNG and JPEG images" }
        val reference = Uuid.random().toString()
        assets[reference] = buildJsonObject {
            put("type", "image")
            put("format", mimeType.substringAfter('/'))
            put("data_base64", data)
        }
        return JsonPrimitive(reference)
    }
    return when (state) {
        is JsonArray -> JsonArray(state.map { typeSafeImageReferences(it, assets) })
        is JsonObject -> JsonObject(state.mapValues { (_, value) -> typeSafeImageReferences(value, assets) })
        is JsonPrimitive -> state
    }
}

internal fun JsonElement.asDecisionText(): String =
    if (this is JsonPrimitive && isString) content else toString()

internal fun JsonElement.multimodalParts(): List<JsonElement>? = when {
    this is JsonArray && any { it.imageUrl() != null } -> this
    imageUrl() != null -> listOf(this)
    else -> null
}
