package com.fajarnuha.klassify.cli

import com.github.ajalt.clikt.testing.test
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.TerminalRecorder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.OsFamily
import kotlin.native.Platform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MainTest {
    @Test
    fun versionOptions() {
        for (flag in listOf("-v", "--version")) {
            val result = Klassify().test(flag)
            assertEquals(0, result.statusCode)
            assertEquals("klassify version $VERSION\n", result.stdout)
        }
    }

    @Test
    fun movesRequireExplicitConfirmationAfterPreview() {
        val plan = Json.parseToJsonElement("""
            {"directory":"/downloads","moves":[{"id":"invoice.pdf","folder":"documents"}]}
        """).jsonObject
        for (answer in listOf(null, "", "n", "not yet", "yes")) {
            val recorder = TerminalRecorder()
            answer?.let { recorder.inputLines.add(it) }
            var applied = false
            confirmFileMoves(plan, Terminal(terminalInterface = recorder)) {
                assertTrue(recorder.stderr().contains("Move 1 file? [y/N]"))
                applied = true
                1
            }
            val output = recorder.stderr()
            assertTrue(output.indexOf("documents/invoice.pdf") in 0 until output.indexOf("Move 1 file?"))
            assertEquals(answer == "yes", applied)
            assertTrue(output.contains(if (applied) "Moved 1 file." else "No files moved."))
        }
    }

    @OptIn(ExperimentalNativeApi::class)
    @Test
    fun recipeRunnerReceivesLargeRequestsOnStandardInput() {
        if (Platform.osFamily == OsFamily.WINDOWS) return
        val input = """{"file":"owner's résumé.pdf","value":"${"x".repeat(150_000)}"}"""
        assertEquals(input, captureRunner(listOf("/bin/cat"), input = input))
    }

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
