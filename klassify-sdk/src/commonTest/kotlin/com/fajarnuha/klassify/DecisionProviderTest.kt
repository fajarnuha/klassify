package com.fajarnuha.klassify

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DecisionProviderTest {
    private enum class Color { RED, BLUE }

    private val isRed by noul("Is this red?")
    private val color by choice<Color>("Which color?")
    private val redness by score("How red?") { levels("No red", "Solid red") }
    private val questions = QuestionSet.fromJson(JsonObject(
        QuestionSet.of(isRed, color, redness).json + ("isRed" to jsonObject(
            """{"type":"noul","instructions":"Is this red?","criteria":{"true":"Mostly red","false":"Mostly another color"}}""",
        )),
    ))
    private val input = JsonArray(listOf(JsonPrimitive("Inspect this"), imageInput(byteArrayOf(1, 2, 3), "image/png")))
    private val expectedAnswers = jsonObject(
        """{"answers":{
            "isRed":{"type":"noul","noul":0.9},
            "color":{"type":"choice","choice":"RED","confidence":0.8,"probabilities":{"RED":0.9,"BLUE":0.1}},
            "redness":{"type":"score","score":0.9,"confidence":0.8,"probabilities":{"0":0.1,"1":0.9},"legend":{"0":"No red","1":"Solid red"}}
        }}""",
    )

    @Test
    fun typeSafeUploadsImageAsReferencedAsset() = runBlocking {
        val http = HttpClient(MockEngine { request ->
            val payload = jsonObject((request.body as TextContent).text)
            val reference = ((payload.getValue("state") as JsonArray)[1]).jsonPrimitive.content
            assertEquals("true", request.headers["TypeSafe-Danger-Zone-Images"])
            assertEquals(JsonPrimitive("Inspect this"), (payload.getValue("state") as JsonArray)[0])
            assertEquals(
                jsonObject("""{"type":"image","format":"png","data_base64":"AQID"}"""),
                payload.getValue("assets").jsonObject[reference],
            )
            respond(expectedAnswers.toString())
        })
        val client = TypeSafeClient("test", http)
        try {
            assertAnswers(client.evaluate(input, questions))
        } finally {
            client.close()
        }
    }

    @Test
    fun openRouterSendsInlineImageWithText() = runBlocking {
        val http = HttpClient(MockEngine { request ->
            assertEquals("https://openrouter.ai/api/alpha/decisions", request.url.toString())
            assertEquals("Bearer test", request.headers[HttpHeaders.Authorization])
            assertNull(request.headers["TypeSafe-Danger-Zone-Images"])
            val payload = jsonObject((request.body as TextContent).text)
            assertEquals(input, payload["state"])
            assertEquals(JsonPrimitive("google/gemini-decision-1"), payload["model"])
            respond(expectedAnswers.toString())
        })
        val client = OpenRouterClient("test", http)
        try {
            assertAnswers(client.evaluate(input, questions, model = "google/gemini-decision-1"))
        } finally {
            client.close()
        }
    }

    @Test
    fun openAISendsDecisionMessagesAndNormalizesAnswers() = runBlocking {
        val http = HttpClient(MockEngine { request ->
            assertEquals("https://api.openai.com/v1/decisions", request.url.toString())
            assertEquals("Bearer test", request.headers[HttpHeaders.Authorization])
            assertEquals(
                jsonObject(
                    """{"model":"gpt-6-luna","input":[{"type":"message","role":"user","content":[
                        {"type":"input_text","text":"Inspect this"},
                        {"type":"input_image","image_url":"data:image/png;base64,AQID"}
                    ]}],"questions":[
                        {"type":"predicate","name":"isRed","instructions":"Is this red?\n\nCriteria: {\"true\":\"Mostly red\",\"false\":\"Mostly another color\"}"},
                        {"type":"choice","name":"color","instructions":"Which color?","choices":[{"value":"RED"},{"value":"BLUE"}]},
                        {"type":"score","name":"redness","instructions":"How red?","levels":[{"label":"No red"},{"label":"Solid red"}]}
                    ]}""",
                ),
                jsonObject((request.body as TextContent).text),
            )
            respond(
                """{"answers":[
                    {"type":"predicate","name":"isRed","probability":0.9},
                    {"type":"choice","name":"color","choice":"RED","confidence":0.8,
                        "probabilities":[{"value":"RED","probability":0.9},{"value":"BLUE","probability":0.1}]},
                    {"type":"score","name":"redness","score":0.9,"confidence":0.8,
                        "probabilities":[{"value":0,"label":"No red","probability":0.1},{"value":1,"label":"Solid red","probability":0.9}]}
                ]}""",
            )
        })
        val client = OpenAIClient("test", http)
        try {
            assertAnswers(client.evaluate(input, questions))
        } finally {
            client.close()
        }
    }

    @Test
    fun googleSendsInlineImageAndUsesGeminiCredentials() = runBlocking {
        val http = HttpClient(MockEngine { request ->
            assertEquals("https://generativelanguage.googleapis.com/v1beta/decisions", request.url.toString())
            assertEquals("test", request.headers["x-goog-api-key"])
            assertNull(request.headers[HttpHeaders.Authorization])
            assertEquals(
                jsonObject(
                    """{"model":"gemini-decision-1","input":[
                        {"type":"text","text":"Inspect this"},
                        {"type":"image","mime_type":"image/png","data":"AQID"}
                    ],"questions":{
                        "isRed":{"type":"noul","instructions":"Is this red?",
                            "criteria":{"true_description":"Mostly red","false_description":"Mostly another color"}},
                        "color":{"type":"choice","instructions":"Which color?","criteria":{"RED":null,"BLUE":null}},
                        "redness":{"type":"score","instructions":"How red?","criteria":["No red","Solid red"]}
                    }}""",
                ),
                jsonObject((request.body as TextContent).text),
            )
            respond(expectedAnswers.toString())
        })
        val client = GoogleClient("test", http)
        try {
            assertAnswers(client.evaluate(input, questions))
        } finally {
            client.close()
        }
    }

    private fun assertAnswers(result: ClassificationResult) {
        assertEquals(expectedAnswers, result.json)
        assertEquals(0.9, result[isRed].probability)
        assertEquals(Color.RED, result[color].value)
        assertEquals(mapOf(Color.RED to 0.9, Color.BLUE to 0.1), result[color].probabilities)
        assertEquals(ScoreAnswer(0.9, 0.8, mapOf(0 to 0.1, 1 to 0.9), mapOf(0 to "No red", 1 to "Solid red")), result[redness])
    }

    private fun jsonObject(value: String): JsonObject = Json.parseToJsonElement(value).jsonObject
}
