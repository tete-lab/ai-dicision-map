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

    @Test
    fun `rejects numeric score paraphrases instead of useful advice`() {
        assertThrows<MalformedLlmResponseException> {
            parser.parse(validJson().replace("성장 기준과 사용자의 기대가 같은 방향입니다.", "이직이 80점이라 유리합니다."))
        }
    }

    @Test
    fun `requires an actionable alternative`() {
        assertThrows<MalformedLlmResponseException> {
            parser.parse(validJson().replace("퇴사 전에 사내 역할 전환이 가능한지도 알아보세요.", ""))
        }
    }

    private fun validJson() = """
        {
          "verdict": {
            "headline": "이직 쪽을 작게 시험해봐도 좋아요.",
            "rationale": "성장 기준과 사용자의 기대가 같은 방향입니다.",
            "encouragement": "면담 한 번으로 가능성을 먼저 확인해보세요.",
            "confidence": "MEDIUM",
            "recommendedOptionId": "change_job",
            "nextAction": "채용 담당자에게 실제 역할 범위를 확인해보세요.",
            "practicalAlternative": "퇴사 전에 사내 역할 전환이 가능한지도 알아보세요.",
            "evidenceRefs": ["새로운 역할을 원한다"]
          },
          "optionProfiles": [
            {"optionId": "stay", "bestWhen": "현재 조직에서 역할을 바꿀 수 있을 때", "evidenceRefs": [], "pros": ["익숙한 환경을 유지할 수 있습니다.", "이직 준비 부담을 미룰 수 있습니다."], "cons": ["성장 속도가 느릴 수 있습니다.", "역할 변화 가능성을 확인해야 합니다."]},
            {"optionId": "change_job", "bestWhen": "새 역할과 조건을 실제 확인했을 때", "evidenceRefs": ["새로운 역할을 원한다"], "pros": ["새 역할에서 성장할 수 있습니다.", "원하는 업무에 도전할 가능성이 있습니다."], "cons": ["새 조직 적응이 필요합니다.", "제안 조건에 불확실성이 있습니다."]}
          ],
          "insights": [
            {
              "type": "KEY_DRIVER",
              "title": "성장 기대",
              "content": "새로운 역할을 원한다는 기대를 실제 담당 업무로 확인해보세요.",
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
