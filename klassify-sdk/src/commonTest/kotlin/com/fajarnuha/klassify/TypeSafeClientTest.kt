package com.fajarnuha.klassify

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TypeSafeClientTest {
    private enum class Team { BILLING, TECHNICAL }

    @Test
    fun sendsJevQuestionsAndReadsAnswers() = runBlocking {
        val team by choice<Team>("Which team?")
        val urgent by noul("Is it urgent?")
        val frustration by score("How frustrated?") {
            levels("Calm", "Angry")
        }
        val questionSet = QuestionSet.of(team, urgent, frustration)
        assertEquals("choice", questionSet.json.getValue("team").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals(setOf("BILLING", "TECHNICAL"),
            questionSet.json.getValue("team").jsonObject.getValue("criteria").jsonObject.keys)
        val http = HttpClient(MockEngine { request ->
            assertEquals("Bearer test", request.headers[HttpHeaders.Authorization])
            assertEquals("https://api.typesafe.ai/v1/systemone", request.url.toString())
            val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            assertEquals("jev-latest", body.getValue("model").jsonPrimitive.content)
            assertEquals("Charged twice!", body.getValue("state").jsonPrimitive.content)
            assertEquals(questionSet.json, body.getValue("questions"))
            respond(
                """{"model":"jev-1.13.0","answers":{"team":{"type":"choice","choice":"BILLING","confidence":0.8,"probabilities":{"BILLING":0.9,"TECHNICAL":0.1}},"urgent":{"type":"noul","noul":0.95},"frustration":{"type":"score","score":1.2,"confidence":0.7,"probabilities":{"0":0.2,"1":0.8},"legend":{"0":"Calm","1":"Angry"}}},"usage":{"input_tokens":10,"output_tokens":5}}""",
                HttpStatusCode.OK,
            )
        })
        val client = TypeSafeClient("test", http)
        try {
            val result = client.evaluate("Charged twice!", team, urgent, frustration)
            assertEquals(Team.BILLING, result[team].value)
            assertEquals(0.9, result[team].probabilities[Team.BILLING])
            assertEquals(0.95, result[urgent].probability)
            assertEquals("Angry", result[frustration].legend[1])
        } finally {
            client.close()
        }
    }

    @Test
    fun rejectsInvalidQuestions() {
        assertFailsWith<IllegalArgumentException> {
            score("How frustrated?") { levels("Only one") }
        }
    }
}
