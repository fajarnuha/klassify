package com.fajarnuha.klassify

import io.ktor.client.HttpClient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

public class GoogleException(public val status: Int, public val responseBody: String) :
    RuntimeException("Google HTTP $status: $responseBody")

/** Google's Gemini Decisions API. Owns and closes the supplied Ktor client. */
public class GoogleClient public constructor(
    apiKey: String,
    httpClient: HttpClient = HttpClient(),
    endpoint: String = "https://generativelanguage.googleapis.com/v1beta/decisions",
) : ClassificationClient {
    override val defaultModel: String = "gemini-decision-1"
    private val transport = DecisionTransport(
        httpClient, endpoint, "Google", apiKey, ::GoogleException, apiKeyHeader = "x-goog-api-key",
    )

    override suspend fun evaluate(state: JsonElement, questions: QuestionSet, model: String): ClassificationResult {
        validateDecisionInput(state, model)
        val payload = buildJsonObject {
            put("model", model)
            put("input", googleInput(state))
            put("questions", JsonObject(questions.json.mapValues { (_, value) -> googleQuestion(value.jsonObject) }))
        }
        return ClassificationResult(transport.post(payload))
    }

    override fun close(): Unit = transport.close()
}

private fun googleInput(state: JsonElement): JsonElement {
    val parts = state.multimodalParts() ?: return JsonPrimitive(state.asDecisionText())
    return JsonArray(parts.map { part ->
        val url = part.imageUrl()
        buildJsonObject {
            if (url == null) {
                put("type", "text")
                put("text", part.asDecisionText())
            } else {
                put("type", "image")
                if (url.startsWith("data:")) {
                    val (mimeType, data) = url.base64Image()
                    put("mime_type", mimeType)
                    put("data", data)
                } else {
                    put("uri", url)
                }
            }
        }
    })
}

private fun googleQuestion(question: JsonObject): JsonObject = buildJsonObject {
    val type = question.getValue("type").jsonPrimitive.content
    put("type", type)
    put("instructions", question.getValue("instructions").asDecisionText())
    when (type) {
        "noul" -> (question["criteria"] as? JsonObject)?.let { criteria ->
            put("criteria", buildJsonObject {
                criteria["true"]?.takeUnless { it == JsonNull }?.let { put("true_description", it.asDecisionText()) }
                criteria["false"]?.takeUnless { it == JsonNull }?.let { put("false_description", it.asDecisionText()) }
            })
        }
        "choice" -> put("criteria", JsonObject(question.getValue("criteria").jsonObject.mapValues { (_, value) ->
            if (value == JsonNull) JsonNull else JsonPrimitive(value.asDecisionText())
        }))
        "score" -> put("criteria", JsonArray(question.getValue("criteria").jsonArray.map {
            JsonPrimitive(it.asDecisionText())
        }))
    }
}
