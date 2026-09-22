package com.fajarnuha.klassify

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.enums.enumEntries
import kotlin.properties.PropertyDelegateProvider
import kotlin.properties.ReadOnlyProperty

/** A named question whose answer type is retained for result lookup. */
public class Question<T> internal constructor(
    public val id: String,
    public val json: JsonObject,
    internal val read: (ClassificationResult) -> T,
)

/** Named Jev questions that can be reused with different states. */
public class QuestionSet private constructor(public val json: JsonObject) {
    public companion object {
        public fun of(vararg questions: Question<*>): QuestionSet {
            val entries = linkedMapOf<String, JsonElement>()
            questions.forEach { question ->
                require(question.id !in entries) { "Duplicate question id: '${question.id}'" }
                entries[question.id] = question.json
            }
            return fromJson(JsonObject(entries))
        }

        public fun fromJson(json: JsonObject): QuestionSet {
            require(json.isNotEmpty()) { "At least one question is required" }
            json.forEach { (name, value) ->
                require(name.isNotBlank()) { "Question id cannot be blank" }
                val question = value as? JsonObject ?: error("Question '$name' must be an object")
                val instructions = question["instructions"]
                require(instructions != null && when (instructions) {
                    is JsonPrimitive -> instructions.isString && instructions.content.isNotBlank()
                    is JsonObject -> instructions.isNotEmpty()
                    is JsonArray -> instructions.isNotEmpty()
                }) { "Question '$name' needs instructions" }
                when (question["type"]?.jsonPrimitive?.content) {
                    "choice" -> {
                        val options = question["criteria"] as? JsonObject
                            ?: error("Choice '$name' needs criteria object")
                        require(options.size in 2..255 && options.keys.all(String::isNotBlank)) {
                            "Choice '$name' needs 2..255 named options"
                        }
                    }
                    "score" -> {
                        val levels = question["criteria"] as? JsonArray
                            ?: error("Score '$name' needs criteria array")
                        require(levels.size in 2..10) { "Score '$name' needs 2..10 levels" }
                    }
                    "noul" -> Unit
                    else -> error("Unknown question type for '$name'")
                }
            }
            return QuestionSet(json)
        }

        public fun parse(text: String): QuestionSet = fromJson(Json.parseToJsonElement(text).jsonObject)
    }
}

private fun <T> namedQuestion(
    create: (String) -> Question<T>,
): PropertyDelegateProvider<Any?, ReadOnlyProperty<Any?, Question<T>>> = PropertyDelegateProvider { _, property ->
    val question = create(property.name)
    ReadOnlyProperty { _, _ -> question }
}

public fun noul(instructions: String): PropertyDelegateProvider<Any?, ReadOnlyProperty<Any?, Question<NoulAnswer>>> =
    namedQuestion { id ->
        Question(id, buildJsonObject {
            put("type", "noul")
            put("instructions", instructions)
        }) { result -> result.noul(id) }
    }

public inline fun <reified E : Enum<E>> choice(
    instructions: String,
): PropertyDelegateProvider<Any?, ReadOnlyProperty<Any?, Question<ChoiceAnswer<E>>>> {
    val entries = enumEntries<E>().toList()
    require(entries.size in 2..255) { "Choice needs 2..255 enum entries" }
    val byName = entries.associateBy { it.name }
    return namedEnumChoice(instructions, byName)
}

@PublishedApi
internal fun <E : Enum<E>> namedEnumChoice(
    instructions: String,
    byName: Map<String, E>,
): PropertyDelegateProvider<Any?, ReadOnlyProperty<Any?, Question<ChoiceAnswer<E>>>> = namedQuestion { id ->
    Question(id, buildJsonObject {
        put("type", "choice")
        put("instructions", instructions)
        put("criteria", JsonObject(byName.keys.associateWith { JsonNull }))
    }) { result ->
        val answer = result.choice(id)
        ChoiceAnswer(
            byName.getValue(answer.value),
            answer.confidence,
            answer.probabilities.mapKeys { (name, _) -> byName.getValue(name) },
        )
    }
}

public class ScoreBuilder public constructor() {
    private val descriptions: MutableList<String> = mutableListOf()

    public fun levels(vararg descriptions: String) {
        require(descriptions.all(String::isNotBlank)) { "Score levels cannot be blank" }
        this.descriptions.addAll(descriptions)
    }

    internal fun build(): JsonArray {
        require(descriptions.size in 2..10) { "Score needs 2..10 levels" }
        return JsonArray(descriptions.map(::JsonPrimitive))
    }
}

public fun score(
    instructions: String,
    block: ScoreBuilder.() -> Unit,
): PropertyDelegateProvider<Any?, ReadOnlyProperty<Any?, Question<ScoreAnswer>>> {
    val criteria = ScoreBuilder().apply(block).build()
    return namedQuestion { id ->
        Question(id, buildJsonObject {
            put("type", "score")
            put("instructions", instructions)
            put("criteria", criteria)
        }) { result -> result.score(id) }
    }
}
