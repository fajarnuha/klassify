package com.fajarnuha.klassify

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OpenRouterClientTest {
    private enum class Team { BILLING, TECHNICAL }

    @Test
    fun evaluatesThroughSharedContract() = runBlocking {
        val team by choice<Team>("Which team?")
        val urgent by noul("Is it urgent?")
        val frustration by score("How frustrated?") { levels("Calm", "Angry") }
        val questions = QuestionSet.of(team, urgent, frustration)
        val states = listOf(
            JsonPrimitive("Charged twice!"),
            Json.parseToJsonElement("""{"ticket":"Charged twice!"}"""),
            Json.parseToJsonElement("""["Charged twice!"]"""),
            JsonPrimitive("Charged twice!"),
        )
        var requests = 0
        val http = HttpClient(MockEngine { request ->
            assertEquals("https://openrouter.ai/api/alpha/decisions", request.url.toString())
            assertEquals("Bearer test", request.headers[HttpHeaders.Authorization])
            assertEquals("application/json", request.body.contentType.toString())
            val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            assertEquals(states[requests], body.getValue("state"))
            assertEquals(if (requests == 3) "~typesafe/jev-latest" else "typesafe/jev-1.13",
                body.getValue("model").jsonPrimitive.content)
            assertEquals(questions.json, body.getValue("questions"))
            requests++
            respond(
                """{"model":"typesafe/jev-1.13-20260917","answers":{"team":{"type":"choice","choice":"BILLING","confidence":0.8,"probabilities":{"BILLING":0.9,"TECHNICAL":0.1}},"urgent":{"type":"noul","noul":0.95},"frustration":{"type":"score","score":1.2,"confidence":0.7,"probabilities":{"0":0.2,"1":0.8},"legend":{"0":"Calm","1":"Angry"}}},"usage":{"input_tokens":10,"output_tokens":5,"cost":0.0001},"id":"gen-dec-test","provider":"TypeSafe"}""",
                HttpStatusCode.OK,
            )
        })
        val client: ClassificationClient = OpenRouterClient("test", http)
        try {
            val results = listOf(
                client.evaluate("Charged twice!", team, urgent, frustration),
                client.evaluate(states[1], team, urgent, frustration),
                client.evaluate(states[2], questions),
                client.evaluate("Charged twice!", questions, model = "~typesafe/jev-latest"),
            )
            results.forEach { result ->
                assertEquals(Team.BILLING, result[team].value)
                assertEquals(0.9, result[team].probabilities[Team.BILLING])
                assertEquals(0.95, result[urgent].probability)
                assertEquals("Angry", result[frustration].legend[1])
                assertEquals("gen-dec-test", result.json.getValue("id").jsonPrimitive.content)
            }
            assertEquals(4, requests)
            assertFailsWith<IllegalArgumentException> { client.evaluate(JsonNull, questions) }
            assertFailsWith<IllegalArgumentException> { client.evaluate(" ", questions) }
            assertFailsWith<IllegalArgumentException> { client.evaluate(states[1], questions, model = " ") }
            assertEquals(4, requests)
        } finally {
            client.close()
        }
    }

    @Test
    fun retriesTransientFailuresAndPreservesHttpErrors() = runBlocking {
        val urgent by noul("Is it urgent?")
        val statuses = listOf(429, 529, 200, 401, 429, 429, 429)
        var requests = 0
        val http = HttpClient(MockEngine {
            val status = statuses[requests++]
            respond(if (status == 200) """{"answers":{"urgent":{"type":"noul","noul":0.95}}}""" else "error $status",
                HttpStatusCode.fromValue(status))
        })
        // Keep the concrete type here to also check inherited overloads and defaults.
        val client = OpenRouterClient("test", http)
        try {
            assertEquals(0.95, client.evaluate("Charged twice!", urgent)[urgent].probability)
            assertEquals(3, requests)
            val unauthorized = assertFailsWith<OpenRouterException> { client.evaluate("test", urgent) }
            assertEquals(401, unauthorized.status)
            assertEquals("error 401", unauthorized.responseBody)
            assertEquals(4, requests)
            val exhausted = assertFailsWith<OpenRouterException> { client.evaluate("test", urgent) }
            assertEquals(429, exhausted.status)
            assertEquals("error 429", exhausted.responseBody)
            assertEquals(7, requests)
        } finally {
            client.close()
        }
    }
}
