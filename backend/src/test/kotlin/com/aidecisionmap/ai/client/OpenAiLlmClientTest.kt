package com.aidecisionmap.ai.client

import com.aidecisionmap.ai.schema.DecisionTurnJsonSchema
import com.aidecisionmap.ai.schema.LlmDecisionStateParser
import com.aidecisionmap.ai.schema.LlmInsightJsonSchema
import com.aidecisionmap.ai.schema.LlmInsightParser
import com.aidecisionmap.conversation.domain.MessageRole
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import jakarta.validation.Validation
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import kotlin.test.assertEquals

class OpenAiLlmClientTest {
    @Test
    fun `parses one valid structured conversation response`() {
        val objectMapper = ObjectMapper().registerKotlinModule()
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        val properties = LlmProperties().apply {
            baseUrl = "https://api.openai.test/v1"
            apiKey = "test-key"
            conversationModel = "test-model"
        }
        val validator = Validation.buildDefaultValidatorFactory().validator
        val parser = LlmDecisionStateParser(objectMapper, validator)
        val client = OpenAiLlmClient(
            builder,
            properties,
            objectMapper,
            parser,
            DecisionTurnJsonSchema(objectMapper),
            LlmInsightParser(objectMapper, validator),
            LlmInsightJsonSchema(objectMapper),
        )

        server.expect(requestTo("https://api.openai.test/v1/responses"))
            .andRespond(withSuccess(providerResponse(objectMapper, validTurnJson()), MediaType.APPLICATION_JSON))

        val turn = client.generateTurn(
            LlmTurnRequest(
                messages = listOf(TranscriptMessage(MessageRole.USER, "이직이 고민돼")),
                previousState = null,
            ),
        )

        assertEquals("다음 질문입니다.", turn.assistantMessage)
        server.verify()
    }

    private fun providerResponse(objectMapper: ObjectMapper, text: String): String =
        objectMapper.writeValueAsString(
            mapOf(
                "output" to listOf(
                    mapOf(
                        "type" to "message",
                        "content" to listOf(mapOf("type" to "output_text", "text" to text)),
                    ),
                ),
            ),
        )

    private fun validTurnJson() = """
        {
          "assistantMessage": "다음 질문입니다.",
          "suggestionMode": "SINGLE",
          "suggestedAnswers": [
            {"label": "현재 상태 유지", "value": "현재 상태 유지"},
            {"label": "새 선택 시도", "value": "새 선택 시도"},
            {"label": "둘 다 비교", "value": "둘 다 비교"},
            {"label": "기타 · 직접 입력", "value": "__custom__"}
          ],
          "state": {
            "decisionTitle": "이직 여부",
            "summary": "이직 여부를 정리하고 있다.",
            "stage": "IDENTIFYING_OPTIONS",
            "options": [],
            "criteria": [],
            "knownFacts": ["이직을 고민 중이다"],
            "assumptions": [],
            "aiInferences": [],
            "missingInformation": ["선택지"],
            "askedQuestions": ["비교 중인 선택지는 무엇인가요?"],
            "userLeaningOptionId": null,
            "userLeaningEvidence": [],
            "progress": 20,
            "readyToAnalyze": false,
            "nextQuestion": "비교 중인 선택지는 무엇인가요?"
          }
        }
    """.trimIndent()
}
