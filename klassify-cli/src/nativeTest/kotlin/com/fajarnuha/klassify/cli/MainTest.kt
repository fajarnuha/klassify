package com.fajarnuha.klassify.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MainTest {
    @Test
    fun fieldSelectionAndEvaluationMetrics() {
        val result = Json.parseToJsonElement("""{
            "answers": {
                "topic": {"choice": "billing"},
                "urgent": {"noul": 0.8},
                "severity": {"score": 1.25}
            }
        }""").jsonObject
        assertEquals(JsonPrimitive("billing"), selectField(result, "answers.topic.choice"))
        assertFailsWith<IllegalStateException> { selectField(result, "answers.missing.choice") }

        val questions = Json.parseToJsonElement("""{
            "topic": {"type": "choice", "criteria": {"billing": null, "other": null}},
            "urgent": {"type": "noul"},
            "severity": {"type": "score", "criteria": ["Low", "Medium", "High"]}
        }""").jsonObject
        val expected = Json.parseToJsonElement("""{
            "topic": "billing", "urgent": true, "severity": 1
        }""").jsonObject
        validateLabels(questions, expected)
        val metrics = mapOf(
            "topic" to EvalMetric("choice"),
            "urgent" to EvalMetric("noul"),
            "severity" to EvalMetric("score"),
        )
        recordMetrics(metrics, expected, result, 0.5)
        assertEquals(1, metrics.getValue("topic").correct)
        assertEquals(1, metrics.getValue("urgent").correct)
        assertEquals(0.25, metrics.getValue("severity").error)
    }
}
