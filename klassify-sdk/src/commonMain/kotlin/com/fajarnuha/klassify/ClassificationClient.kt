package com.fajarnuha.klassify

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.client.statement.bodyAsText
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
    private val httpClient: HttpClient,
    private val endpoint: String,
    override val defaultModel: String,
    private val service: String,
    private val exception: (Int, String) -> RuntimeException,
) : ClassificationClient {
    private val token: String = apiKey.also { require(it.isNotBlank()) { "API key cannot be blank" } }

    override suspend fun evaluate(
        state: JsonElement,
        questions: QuestionSet,
        model: String,
    ): ClassificationResult {
        require(state is JsonPrimitive && state.isString && state.content.isNotBlank() || state is JsonObject || state is JsonArray) {
            "State must be a string, object, or array"
        }
        require(model.isNotBlank()) { "Model cannot be blank" }
        val payload = buildJsonObject {
            put("state", state)
            put("model", model)
            put("questions", questions.json)
        }.toString()
        repeat(3) { attempt ->
            val response = httpClient.post(endpoint) {
                header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody(payload)
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                return ClassificationResult(Json.parseToJsonElement(body).let {
                    it as? JsonObject ?: error("$service response must be an object")
                })
            }
            if (response.status.value !in setOf(429, 529) || attempt == 2) {
                throw exception(response.status.value, body)
            }
            delay(250L shl attempt)
        }
        error("Unreachable")
    }

    override fun close(): Unit = httpClient.close()
}
