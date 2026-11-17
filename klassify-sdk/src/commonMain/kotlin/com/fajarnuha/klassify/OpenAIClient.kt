package com.fajarnuha.klassify

import io.ktor.client.HttpClient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

public class OpenAIException(public val status: Int, public val responseBody: String) :
    RuntimeException("OpenAI HTTP $status: $responseBody")

/** OpenAI's Decisions API. Owns and closes the supplied Ktor client. */
public class OpenAIClient public constructor(
    apiKey: String,
    httpClient: HttpClient = HttpClient(),
    endpoint: String = "https://api.openai.com/v1/decisions",
) : ClassificationClient {
    override val defaultModel: String = "gpt-6-luna"
    private val transport = DecisionTransport(httpClient, endpoint, "OpenAI", apiKey, ::OpenAIException)

    override suspend fun evaluate(state: JsonElement, questions: QuestionSet, model: String): ClassificationResult {
        validateDecisionInput(state, model)
        val payload = buildJsonObject {
            put("model", model)
            put("input", openAIInput(state))
            put("questions", buildJsonArray {
                questions.json.forEach { (id, value) -> add(openAIQuestion(id, value.jsonObject)) }
            })
        }
        val response = transport.post(payload)
        val answers = buildJsonObject {
            response.getValue("answers").jsonArray.forEach { value ->
                val answer = value.jsonObject
                put(answer.getValue("name").jsonPrimitive.content, normalizeOpenAIAnswer(answer))
            }
        }
        return ClassificationResult(JsonObject(response + ("answers" to answers)))
    }

    override fun close(): Unit = transport.close()
}

private fun openAIInput(state: JsonElement): JsonElement {
    val parts = state.multimodalParts()
    if (parts == null && state is JsonPrimitive) return state
    return buildJsonArray {
        add(buildJsonObject {
            put("type", "message")
            put("role", "user")
            put("content", JsonArray((parts ?: listOf(state)).map { part ->
                val url = part.imageUrl()
                buildJsonObject {
                    when {
                        url != null -> {
                            put("type", "input_image")
                            put("image_url", url)
                        }
                        part is JsonObject || part is JsonArray && part.all { it is JsonObject } -> {
                            put("type", "input_json")
                            put("data", part)
                        }
                        else -> {
                            put("type", "input_text")
                            put("text", part.asDecisionText())
                        }
                    }
                }
            }))
        })
    }
}

private fun openAIQuestion(id: String, question: JsonObject): JsonObject = buildJsonObject {
    val type = question.getValue("type").jsonPrimitive.content
    put("type", if (type == "noul") "predicate" else type)
    put("name", id)
    val instructions = question.getValue("instructions").asDecisionText()
    val noulCriteria = if (type == "noul") question["criteria"] as? JsonObject else null
    put("instructions", if (noulCriteria.isNullOrEmpty()) instructions else "$instructions\n\nCriteria: $noulCriteria")
    when (type) {
        "choice" -> put("choices", buildJsonArray {
            question.getValue("criteria").jsonObject.forEach { (name, description) ->
                add(buildJsonObject {
                    put("value", name)
                    if (description != JsonNull) put("description", description.asDecisionText())
                })
            }
        })
        "score" -> put("levels", buildJsonArray {
            question.getValue("criteria").jsonArray.forEach { level ->
                add(buildJsonObject { put("label", level.asDecisionText()) })
            }
        })
    }
}

private fun normalizeOpenAIAnswer(answer: JsonObject): JsonObject = buildJsonObject {
    when (val type = answer.getValue("type").jsonPrimitive.content) {
        "predicate" -> {
            put("type", "noul")
            put("noul", answer.getValue("probability"))
        }
        "choice", "score" -> {
            val outcomes = answer.getValue("probabilities").jsonArray.map { it.jsonObject }
            put("type", type)
            put(type, answer.getValue(type))
            put("confidence", answer.getValue("confidence"))
            put("probabilities", JsonObject(outcomes.associate {
                it.getValue("value").jsonPrimitive.content to it.getValue("probability")
            }))
            if (type == "score") {
                put("legend", JsonObject(outcomes.associate {
                    it.getValue("value").jsonPrimitive.content to it.getValue("label")
                }))
            }
        }
        else -> answer.forEach { (key, value) -> put(key, value) }
    }
}
