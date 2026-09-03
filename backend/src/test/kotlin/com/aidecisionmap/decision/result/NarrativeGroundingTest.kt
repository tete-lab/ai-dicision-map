package com.aidecisionmap.decision.result

import com.aidecisionmap.ai.client.*
import com.aidecisionmap.ai.schema.MalformedLlmResponseException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal

class NarrativeGroundingTest {
    private val fact = "매일 업무용으로 쓰려 하고 기존 제품은 고장났다"
    private val request = LlmNarrativeRequest(
        "구매할까 말까", listOf(InsightOptionInput("buy", "구매", null), InsightOptionInput("wait", "보류", null)),
        emptyList(), emptyList(), BigDecimal("100"), null, "NEEDS_VERIFICATION", null, emptyList(),
        listOf(fact), emptyList(), emptyList(), listOf("총비용"),
    )
    private val narrative = GeneratedNarrative(
        GeneratedVerdict("총비용 확인 후 구매를 검토해보세요", "업무에 필요한 기능을 회복할 수 있어요", "조건부터 확인해봐요",
            NarrativeConfidence.MEDIUM, "buy", "수리 견적과 구매 총비용을 비교하세요", "수리 가능 여부를 먼저 확인해보세요", listOf(fact)),
        listOf(GeneratedOptionProfile("buy", emptyList(), emptyList(), evidenceRefs = listOf(fact)),
            GeneratedOptionProfile("wait", emptyList(), emptyList())),
        emptyList(), emptyList(), emptyList(),
    )

    @Test fun `accepts a context referenced recommendation`() {
        NarrativeGrounding.validate(narrative, request)
    }
    @Test fun `rejects fabricated evidence even when fluent`() {
        assertThrows<MalformedLlmResponseException> {
            NarrativeGrounding.validate(narrative.copy(verdict = narrative.verdict.copy(evidenceRefs = listOf("공식 조사에서 생산성이 두 배"))), request)
        }
    }
    @Test fun `rejects a missing or unknown alternative`() {
        assertThrows<MalformedLlmResponseException> {
            NarrativeGrounding.validate(narrative.copy(optionProfiles = narrative.optionProfiles.take(1)), request)
        }
        assertThrows<MalformedLlmResponseException> {
            NarrativeGrounding.validate(narrative.copy(verdict = narrative.verdict.copy(recommendedOptionId = "rent")), request)
        }
    }
    @Test fun `scores alone do not justify a recommendation`() {
        assertThrows<MalformedLlmResponseException> {
            NarrativeGrounding.validate(narrative.copy(verdict = narrative.verdict.copy(evidenceRefs = emptyList())), request)
        }
    }
    @Test fun `permits low confidence verification rather than forced recommendation`() {
        NarrativeGrounding.validate(narrative.copy(verdict = narrative.verdict.copy(
            recommendedOptionId = null, evidenceRefs = emptyList(), confidence = NarrativeConfidence.LOW)), request)
    }
}
