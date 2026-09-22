package com.fajarnuha.klassify

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

public data class ChoiceAnswer<T>(
    public val value: T,
    public val confidence: Double,
    public val probabilities: Map<T, Double>,
)

public data class NoulAnswer(public val probability: Double)

public data class ScoreAnswer(
    public val value: Double,
    public val confidence: Double,
    public val probabilities: Map<Int, Double>,
    public val legend: Map<Int, String>,
)

public class ClassificationResult public constructor(public val json: JsonObject) {
    public operator fun <T> get(question: Question<T>): T = question.read(this)

    private fun answer(id: String, type: String): JsonObject {
        val value = json["answers"]?.jsonObject?.get(id)?.jsonObject
            ?: error("Missing answer '$id'")
        require(value["type"]?.jsonPrimitive?.content == type) { "Answer '$id' is not $type" }
        return value
    }

    public fun choice(id: String): ChoiceAnswer<String> = answer(id, "choice").let {
        ChoiceAnswer(
            it.getValue("choice").jsonPrimitive.content,
            it.getValue("confidence").jsonPrimitive.double,
            it.getValue("probabilities").jsonObject.mapValues { (_, value) -> value.jsonPrimitive.double },
        )
    }

    public fun noul(id: String): NoulAnswer = answer(id, "noul").let {
        NoulAnswer(it.getValue("noul").jsonPrimitive.double)
    }

    public fun score(id: String): ScoreAnswer = answer(id, "score").let {
        ScoreAnswer(
            it.getValue("score").jsonPrimitive.double,
            it.getValue("confidence").jsonPrimitive.double,
            it.getValue("probabilities").jsonObject.mapKeys { (key, _) -> key.toInt() }
                .mapValues { (_, value) -> value.jsonPrimitive.double },
            it.getValue("legend").jsonObject.mapKeys { (key, _) -> key.toInt() }
                .mapValues { (_, value) -> value.jsonPrimitive.content },
        )
    }
}
