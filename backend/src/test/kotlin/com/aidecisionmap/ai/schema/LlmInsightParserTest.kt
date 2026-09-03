package com.aidecisionmap.ai.schema

import com.aidecisionmap.ai.client.DecisionRuleOutcome
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import jakarta.validation.Validation
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class LlmInsightParserTest {
    private val parser = LlmInsightParser(
        ObjectMapper().registerKotlinModule(),
        Validation.buildDefaultValidatorFactory().validator,
    )

    @Test
    fun `parses and orders a grounded supportive narrative`() {
        val narrative = parser.parse(validJson())

        assertEquals("이직 쪽을 작게 시험해봐도 좋아요.", narrative.verdict.headline)
        assertEquals(2, narrative.optionProfiles.size)
        assertEquals(listOf(1, 2), narrative.insights.map { it.priority })
        assertEquals(DecisionRuleOutcome.SELECT, narrative.decisionRules.first().outcome)
    }

    @Test
    fun `rejects duplicate insight priorities`() {
        assertThrows<MalformedLlmResponseException> {
            parser.parse(validJson().replace("\"priority\": 2", "\"priority\": 1"))
        }
    }

    private fun validJson() = """
        {
          "verdict": {
            "headline": "이직 쪽을 작게 시험해봐도 좋아요.",
            "rationale": "성장 기준과 사용자의 기대가 같은 방향입니다.",
            "encouragement": "면담 한 번으로 가능성을 먼저 확인해보세요.",
            "confidence": "MEDIUM"
          },
          "optionProfiles": [
            {"optionId": "stay", "pros": ["안정성이 유지됩니다.", "전환 비용이 없습니다."], "cons": ["성장 속도가 느릴 수 있습니다.", "역할 변화가 제한적입니다."]},
            {"optionId": "change_job", "pros": ["새 역할에서 성장할 수 있습니다.", "보상 상승 가능성이 있습니다."], "cons": ["새 조직 적응이 필요합니다.", "제안 조건에 불확실성이 있습니다."]}
          ],
          "insights": [
            {
              "type": "KEY_DRIVER",
              "title": "성장 기대",
              "content": "이직 선택의 성장 점수가 높습니다.",
              "evidenceRefs": ["새로운 역할을 원한다"],
              "assumptionRefs": [],
              "confidence": "MEDIUM",
              "whatCouldChange": "실제 역할 범위가 다르면 달라집니다.",
              "priority": 1
            },
            {
              "type": "TRADE_OFF",
              "title": "안정성과 성장",
              "content": "안정성을 일부 내려놓는 대신 성장 폭을 기대합니다.",
              "evidenceRefs": [],
              "assumptionRefs": ["제안 조건이 유지된다는 가정"],
              "confidence": "LOW",
              "whatCouldChange": "최종 제안 조건이 바뀌면 달라집니다.",
              "priority": 2
            }
          ],
          "actionPlan": [
            {"order": 1, "timing": "오늘", "task": "역할 확인", "why": "기대와 현실을 맞춥니다.", "evidenceNeeded": ["직무 기술서"], "doneWhen": "질문 세 개에 답을 받았을 때"},
            {"order": 2, "timing": "이번 주", "task": "조건 비교", "why": "감당할 부담을 확인합니다.", "evidenceNeeded": ["최종 제안서"], "doneWhen": "핵심 조건을 표로 비교했을 때"}
          ],
          "decisionRules": [
            {"outcome": "SELECT", "condition": "성장 조건이 실제 확인될 때"}
          ]
        }
    """.trimIndent()
}
