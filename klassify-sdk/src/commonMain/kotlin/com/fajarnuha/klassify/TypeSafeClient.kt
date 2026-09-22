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

public class TypeSafeException(public val status: Int, public val responseBody: String) :
    RuntimeException("TypeSafe HTTP $status: $responseBody")

/** A client owns and closes its Ktor client, including one passed by the caller. */
public class TypeSafeClient public constructor(
    apiKey: String,
    private val httpClient: HttpClient = HttpClient(),
    private val endpoint: String = "https://api.typesafe.ai/v1/systemone",
) {
    private val token: String = apiKey.also { require(it.isNotBlank()) { "API key cannot be blank" } }

    public suspend fun evaluate(state: String, vararg questions: Question<*>): ClassificationResult =
        evaluate(state, QuestionSet.of(*questions))

    public suspend fun evaluate(state: JsonElement, vararg questions: Question<*>): ClassificationResult =
        evaluate(state, QuestionSet.of(*questions))

    public suspend fun evaluate(
        state: String,
        questions: QuestionSet,
        model: String = "jev-latest",
    ): ClassificationResult = evaluate(JsonPrimitive(state), questions, model)

    public suspend fun evaluate(
        state: JsonElement,
        questions: QuestionSet,
        model: String = "jev-latest",
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
                    it as? JsonObject ?: error("TypeSafe response must be an object")
                })
            }
            if (response.status.value !in setOf(429, 529) || attempt == 2) {
                throw TypeSafeException(response.status.value, body)
            }
            delay(250L shl attempt)
        }
        error("Unreachable")
    }

    public fun close(): Unit = httpClient.close()
}
