package com.aidecisionmap.ai.schema

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import jakarta.validation.Validation
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LlmDecisionStateParserTest {
    private val objectMapper = ObjectMapper().registerKotlinModule()
    private val parser = LlmDecisionStateParser(
        objectMapper,
        Validation.buildDefaultValidatorFactory().validator,
    )

    @Test
    fun `parses a valid structured decision turn`() {
        val turn = parser.parse(validJson())

        assertEquals("이직 여부", turn.state.decisionTitle)
        assertEquals(2, turn.state.options.size)
        assertEquals(4, turn.suggestedAnswers.size)
        assertEquals("가장 중요하게 보는 기준은 무엇인가요?", turn.state.nextQuestion)
    }

    @Test
    fun `rejects malformed and unknown JSON fields`() {
        assertThrows<MalformedLlmResponseException> { parser.parse("{not-json}") }
        assertThrows<MalformedLlmResponseException> {
            parser.parse(validJson().replace("\"assistantMessage\":", "\"unexpected\": true, \"assistantMessage\":"))
        }
    }

    @Test
    fun `rejects an internal English id as a user facing answer`() {
        val json = validJson().replace(
            "{\"label\": \"성장 기회\", \"value\": \"성장 기회\"}",
            "{\"label\": \"성장 기회\", \"value\": \"change_job\"}",
        )
        assertThrows<MalformedLlmResponseException> { parser.parse(json) }
    }

    @Test
    fun `rejects a ready state without enough options and criteria`() {
        val root = objectMapper.readTree(validJson())
        val state = root.path("state") as com.fasterxml.jackson.databind.node.ObjectNode
        (state.path("options") as com.fasterxml.jackson.databind.node.ArrayNode).remove(1)
        state.putNull("userLeaningOptionId")
        (state.path("userLeaningEvidence") as com.fasterxml.jackson.databind.node.ArrayNode).removeAll()
        (root.path("suggestedAnswers") as com.fasterxml.jackson.databind.node.ArrayNode).removeAll()
        state.put("readyToAnalyze", true)
        state.put("nextQuestion", "")

        val exception = assertThrows<MalformedLlmResponseException> { parser.parse(root.toString()) }
        assertTrue(exception.message!!.contains("at least two options"))
    }

    private fun validJson() = """
        {
          "assistantMessage": "선택지는 두 가지로 이해했어요. 가장 중요하게 보는 기준은 무엇인가요?",
          "suggestionMode": "SINGLE",
          "suggestedAnswers": [
            {"label": "성장 기회", "value": "성장 기회"},
            {"label": "연봉과 보상", "value": "연봉과 보상"},
            {"label": "업무 환경", "value": "업무 환경"},
            {"label": "기타 · 직접 입력", "value": "__custom__"}
          ],
          "state": {
            "decisionTitle": "이직 여부",
            "summary": "현재 회사에 남는 것과 이직을 비교하고 있다.",
            "stage": "COLLECTING_CRITERIA",
            "options": [
              {"id": "stay", "name": "현재 회사에 남기"},
              {"id": "move", "name": "새 회사로 이직"}
            ],
            "criteria": [],
            "knownFacts": ["이직을 고민 중이다"],
            "assumptions": [],
            "aiInferences": [],
            "missingInformation": ["중요 기준"],
            "askedQuestions": ["가장 중요하게 보는 기준은 무엇인가요?"],
            "userLeaningOptionId": "move",
            "userLeaningEvidence": ["새로운 환경에서 성장하고 싶다고 말했다"],
            "progress": 35,
            "readyToAnalyze": false,
            "nextQuestion": "가장 중요하게 보는 기준은 무엇인가요?"
          }
        }
    """.trimIndent()
}
