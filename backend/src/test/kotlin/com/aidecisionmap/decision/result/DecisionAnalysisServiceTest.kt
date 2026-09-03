package com.aidecisionmap.decision.result

import com.aidecisionmap.ai.client.*
import com.aidecisionmap.decision.api.GuidanceBasis
import com.aidecisionmap.decision.api.NarrativeStatus
import com.aidecisionmap.decision.domain.*
import com.aidecisionmap.decision.engine.DecisionEngine
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import java.math.BigDecimal
import kotlin.test.*

class DecisionAnalysisServiceTest {
    private val mapper = ObjectMapper().registerKotlinModule()
    private val sessions = mock<DecisionSessionRepository>()
    private val options = mock<DecisionOptionRepository>()
    private val criteria = mock<DecisionCriterionRepository>()
    private val scores = mock<CriterionOptionScoreRepository>()
    private val insights = mock<DecisionInsightRepository>()
    private val llm = mock<LlmClient>()
    private val service = DecisionAnalysisService(sessions, options, criteria, scores, insights, DecisionEngine(), llm, mapper)
    private val fact = "필수 업무용 기기가 고장났어요"
    private val state = DecisionState(
        "기기를 구매할까", "업무 재개 방법", DecisionStage.ANALYZED,
        listOf(DecisionOption("buy", "구매한다"), DecisionOption("wait", "구매하지 않는다")),
        listOf(DecisionCriterion("cost", "비용", 1.0, DecisionSourceType.USER_ASSUMPTION, 0.5)),
        listOf(fact), emptyList(), emptyList(), listOf("구매 총비용"),
        progress = 100, readyToAnalyze = true, nextQuestion = "",
    )
    private val session = DecisionSessionEntity("test", state.decisionTitle, DecisionStage.ANALYZED,
        stateJson = mapper.writeValueAsString(state), promptVersion = "test")

    init {
        val optionEntities = state.options.map { DecisionOptionEntity(session, it.id, it.name) }
        val criterion = DecisionCriterionEntity(session, "cost", "비용", BigDecimal.ONE, DecisionSourceType.USER_ASSUMPTION)
        whenever(sessions.findByPublicId("test")).thenReturn(session)
        whenever(options.findBySessionOrderByIdAsc(session)).thenReturn(optionEntities)
        whenever(criteria.findBySessionOrderByIdAsc(session)).thenReturn(listOf(criterion))
        whenever(scores.findByOptionSessionOrderByIdAsc(session)).thenReturn(optionEntities.mapIndexed { i, option ->
            CriterionOptionScoreEntity(criterion, option, BigDecimal(if (i == 0) 80 else 20),
                "사용자 상대 평가", DecisionSourceType.USER_ASSUMPTION)
        })
        whenever(insights.findBySessionOrderByPriorityAscIdAsc(session)).thenReturn(emptyList())
    }

    private fun narrative() = GeneratedNarrative(
        GeneratedVerdict("수리와 구매 총비용을 비교해보세요", "업무를 다시 시작하는 데 필요한 선택이에요", "작게 확인하면 방향이 선명해져요",
            NarrativeConfidence.MEDIUM, "buy", "수리 견적을 받아보세요", "임시 대체 기기를 쓸 수 있는지 확인해보세요", listOf(fact)),
        state.options.map { GeneratedOptionProfile(it.id, listOf("업무 재개 방법을 비교할 수 있어요", "필요한 기능을 확인할 수 있어요"),
            listOf("총비용 확인이 필요해요", "실제 이용 가능 여부를 확인해야 해요"), "필수 기능을 충족할 때", listOf(fact)) },
        emptyList(), emptyList(), emptyList(),
    )

    @Test fun `configuration failure remains explicit after reloading instead of a score endorsement`() {
        whenever(llm.generateNarrative(any())).thenThrow(LlmConfigurationException("missing key"))
        val result = service.enrich("test").result
        assertEquals(NarrativeStatus.FALLBACK, result.narrativeStatus)
        assertEquals("LLM_NOT_CONFIGURED", result.narrativeError)
        assertNull(result.guidance.encouragedOptionId)
        assertEquals(GuidanceBasis.NEEDS_VERIFICATION, result.guidance.basis)
        assertFalse(result.optionProfiles.flatMap { it.pros + it.cons }.any { it.contains("80점") })
        assertEquals(NarrativeStatus.FALLBACK, service.getResult("test").result.narrativeStatus)
        assertEquals("LOW", result.evidenceQuality.level)
    }

    @Test fun `a retry replaces fallback with grounded advice and keeps scores separate`() {
        whenever(llm.generateNarrative(any())).thenThrow(LlmUnavailableException("temporarily unavailable")).thenReturn(narrative())
        service.enrich("test")
        val result = service.enrich("test").result
        assertEquals(NarrativeStatus.READY, result.narrativeStatus)
        assertNull(result.narrativeError)
        assertEquals(GuidanceBasis.CONTEXTUAL, result.guidance.basis)
        assertEquals("buy", result.guidance.encouragedOptionId)
        assertEquals("수리 견적을 받아보세요", result.guidance.nextAction)
        assertEquals(listOf(fact), result.guidance.evidenceRefs)
        assertEquals(NarrativeStatus.READY, service.getResult("test").result.narrativeStatus)
    }

    @Test fun `unsupported evidence is not published as AI advice`() {
        whenever(llm.generateNarrative(any())).thenReturn(narrative().let { it.copy(
            verdict = it.verdict.copy(evidenceRefs = listOf("확인하지 않은 전문가 추천"))
        ) })
        val result = service.enrich("test").result
        assertEquals("LLM_EVIDENCE_INVALID", result.narrativeError)
        assertEquals(NarrativeStatus.FALLBACK, result.narrativeStatus)
        assertNull(result.guidance.encouragedOptionId)
    }
}
