package com.fajarnuha.klassify

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Clients evaluate the same questions and own their Ktor client, including one passed by the caller. */
public interface ClassificationClient {
    public val defaultModel: String

    public suspend fun evaluate(state: String, vararg questions: Question<*>): ClassificationResult =
        evaluate(state, QuestionSet.of(*questions))

    public suspend fun evaluate(state: JsonElement, vararg questions: Question<*>): ClassificationResult =
        evaluate(state, QuestionSet.of(*questions))

    public suspend fun evaluate(
        state: String,
        questions: QuestionSet,
        model: String = defaultModel,
    ): ClassificationResult = evaluate(JsonPrimitive(state), questions, model)

    public suspend fun evaluate(
        state: JsonElement,
        questions: QuestionSet,
        model: String = defaultModel,
    ): ClassificationResult

    public fun close()
}

internal class SystemOneClient(
    apiKey: String,
    httpClient: HttpClient,
    endpoint: String,
    override val defaultModel: String,
    service: String,
    exception: (Int, String) -> RuntimeException,
    private val typeSafeImages: Boolean = false,
) : ClassificationClient {
    private val transport = DecisionTransport(httpClient, endpoint, service, apiKey, exception)

    override suspend fun evaluate(
        state: JsonElement,
        questions: QuestionSet,
        model: String,
    ): ClassificationResult {
        validateDecisionInput(state, model)
        val assets = linkedMapOf<String, JsonElement>()
        val wireState = when {
            typeSafeImages -> typeSafeImageReferences(state, assets)
            state.imageUrl() != null -> JsonArray(listOf(state))
            else -> state
        }
        val payload = buildJsonObject {
            put("state", wireState)
            put("model", model)
            put("questions", questions.json)
            if (assets.isNotEmpty()) put("assets", JsonObject(assets))
        }
        return ClassificationResult(transport.post(payload, enableTypeSafeImages = assets.isNotEmpty()))
    }

    override fun close(): Unit = transport.close()
}

internal fun validateDecisionInput(state: JsonElement, model: String) {
    require(state is JsonPrimitive && state.isString && state.content.isNotBlank() || state is JsonObject || state is JsonArray) {
        "State must be a string, object, or array"
    }
    require(model.isNotBlank()) { "Model cannot be blank" }
}

internal class DecisionTransport(
    private val httpClient: HttpClient,
    private val endpoint: String,
    private val service: String,
    apiKey: String,
    private val exception: (Int, String) -> RuntimeException,
    private val apiKeyHeader: String = HttpHeaders.Authorization,
) {
    private val credential = apiKey.also { require(it.isNotBlank()) { "API key cannot be blank" } }
    private val authorization = if (apiKeyHeader == HttpHeaders.Authorization) "Bearer $credential" else credential

    suspend fun post(payload: JsonObject, enableTypeSafeImages: Boolean = false): JsonObject {
        val requestBody = payload.toString()
        repeat(3) { attempt ->
            val response = httpClient.post(endpoint) {
                header(apiKeyHeader, authorization)
                if (enableTypeSafeImages) header("TypeSafe-Danger-Zone-Images", "true")
                contentType(ContentType.Application.Json)
                setBody(requestBody)
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                return Json.parseToJsonElement(body) as? JsonObject ?: error("$service response must be an object")
            }
            if (response.status.value !in setOf(429, 529) || attempt == 2) {
                throw exception(response.status.value, body)
            }
            delay(250L shl attempt)
        }
        error("Unreachable")
    }

    fun close(): Unit = httpClient.close()
}
