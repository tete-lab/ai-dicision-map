package com.aidecisionmap.decision.result

import com.aidecisionmap.ai.client.GeneratedNarrative
import com.aidecisionmap.ai.client.InsightAssessmentInput
import com.aidecisionmap.ai.client.InsightCriterionInput
import com.aidecisionmap.ai.client.InsightOptionInput
import com.aidecisionmap.ai.client.LlmClient
import com.aidecisionmap.ai.client.LlmNarrativeRequest
import com.aidecisionmap.ai.client.LlmConfigurationException
import com.aidecisionmap.ai.client.LlmUnavailableException
import com.aidecisionmap.ai.schema.MalformedLlmResponseException
import com.aidecisionmap.decision.api.AnalyzeDecisionRequest
import com.aidecisionmap.decision.api.DecisionResult
import com.aidecisionmap.decision.api.DecisionResultResponse
import com.aidecisionmap.decision.api.GuidanceBasis
import com.aidecisionmap.decision.api.MissingAssessmentDto
import com.aidecisionmap.decision.api.NarrativeStatus
import com.aidecisionmap.decision.api.ResultActionStep
import com.aidecisionmap.decision.api.ResultAssessment
import com.aidecisionmap.decision.api.ResultCompleteness
import com.aidecisionmap.decision.api.ResultCriterion
import com.aidecisionmap.decision.api.ResultDecisionRule
import com.aidecisionmap.decision.api.ResultEvidenceQuality
import com.aidecisionmap.decision.api.ResultGuidance
import com.aidecisionmap.decision.api.ResultInsight
import com.aidecisionmap.decision.api.ResultOption
import com.aidecisionmap.decision.api.ResultOptionProfile
import com.aidecisionmap.decision.api.ResultScenarioForecast
import com.aidecisionmap.decision.api.ResultScenarioPoint
import com.aidecisionmap.decision.domain.DecisionSessionEntity
import com.aidecisionmap.decision.domain.DecisionSessionRepository
import com.aidecisionmap.decision.domain.DecisionSourceType
import com.aidecisionmap.decision.domain.DecisionStage
import com.aidecisionmap.decision.domain.DecisionState
import com.aidecisionmap.decision.engine.DecisionEngine
import com.aidecisionmap.decision.engine.DecisionEngineInput
import com.aidecisionmap.decision.engine.DecisionEngineResult
import com.aidecisionmap.decision.engine.EngineAssessment
import com.aidecisionmap.decision.engine.EngineCriterion
import com.aidecisionmap.decision.engine.EngineOption
import com.aidecisionmap.decision.service.DecisionSessionNotFoundException
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode

@Service
class DecisionAnalysisService(
    private val sessionRepository: DecisionSessionRepository,
    private val optionRepository: DecisionOptionRepository,
    private val criterionRepository: DecisionCriterionRepository,
    private val scoreRepository: CriterionOptionScoreRepository,
    private val insightRepository: DecisionInsightRepository,
    private val engine: DecisionEngine,
    private val llmClient: LlmClient,
    private val objectMapper: ObjectMapper,
) {
    @Transactional
    fun analyze(sessionId: String, request: AnalyzeDecisionRequest): DecisionResultResponse {
        val session = findSession(sessionId)
        val state = readState(session)
        if (!state.readyToAnalyze && state.stage != DecisionStage.ANALYZED) throw DecisionNotReadyException(sessionId)

        val engineResult = calculate(state, request.assessments.map {
            EngineAssessment(it.optionId, it.criterionId, it.score)
        })
        val scoreByOption = engineResult.optionScores.associateBy { it.optionId }

        clearPreviousResult(session)
        val optionEntities = optionRepository.saveAll(state.options.map { option ->
            DecisionOptionEntity(
                session = session,
                optionKey = option.id,
                name = option.name,
                score = scoreByOption.getValue(option.id).score,
            )
        }).associateBy { it.optionKey }
        val criterionEntities = criterionRepository.saveAll(state.criteria.map { criterion ->
            DecisionCriterionEntity(
                session = session,
                criterionKey = criterion.id,
                name = criterion.name,
                weight = engineResult.normalizedWeights.getValue(criterion.id),
                sourceType = criterion.sourceType,
            )
        }).associateBy { it.criterionKey }
        val assessmentEntities = scoreRepository.saveAll(request.assessments.map { assessment ->
            CriterionOptionScoreEntity(
                criterion = criterionEntities.getValue(assessment.criterionId),
                option = optionEntities.getValue(assessment.optionId),
                score = decimal(assessment.score),
                reason = assessment.reason?.trim()?.takeIf(String::isNotEmpty),
                sourceType = assessment.sourceType,
            )
        })

        val analyzedState = state.copy(stage = DecisionStage.ANALYZED, progress = 100)
        session.stage = DecisionStage.ANALYZED
        session.progress = 100
        session.stateJson = objectMapper.writeValueAsString(analyzedState)
        session.resultNarrativeJson = null
        sessionRepository.save(session)

        return DecisionResultResponse(
            DecisionStage.ANALYZED,
            buildResult(session, analyzedState, optionEntities.values.toList(), criterionEntities.values.toList(), assessmentEntities, engineResult),
        )
    }

    @Transactional
    fun enrich(sessionId: String): DecisionResultResponse {
        val session = findSession(sessionId)
        val state = readState(session)
        val options = optionRepository.findBySessionOrderByIdAsc(session)
        val criteria = criterionRepository.findBySessionOrderByIdAsc(session)
        if (session.stage != DecisionStage.ANALYZED || options.isEmpty() || criteria.isEmpty()) {
            throw DecisionResultNotFoundException(sessionId)
        }
        val assessments = scoreRepository.findByOptionSessionOrderByIdAsc(session)
        val engineResult = calculateFromEntities(options, criteria, assessments)
        val guidance = fallbackGuidance(state, options)
        val scoreByOption = engineResult.optionScores.associateBy { it.optionId }
        val narrativeRequest = LlmNarrativeRequest(
                decisionTitle = state.decisionTitle,
                options = options.map { InsightOptionInput(it.optionKey, it.name, scoreByOption.getValue(it.optionKey).score) },
                criteria = criteria.map { InsightCriterionInput(it.criterionKey, it.name, it.weight) },
                assessments = assessments.map {
                    InsightAssessmentInput(it.option.optionKey, it.criterion.criterionKey, it.score, it.reason, it.sourceType.name)
                },
                completenessPercent = engineResult.completeness.weightedCoveragePercent,
                encouragedOptionId = guidance.encouragedOptionId,
                encouragementBasis = guidance.basis.name,
                userLeaningOptionId = state.userLeaningOptionId,
                userLeaningEvidence = state.userLeaningEvidence,
                knownFacts = state.knownFacts,
                assumptions = state.assumptions,
                aiInferences = state.aiInferences,
                missingInformation = state.missingInformation,
            )
        val narrative = try {
            llmClient.generateNarrative(narrativeRequest).also { NarrativeGrounding.validate(it, narrativeRequest) }
        } catch (error: RuntimeException) {
            val code = when (error) {
                is LlmConfigurationException -> "LLM_NOT_CONFIGURED"
                is MalformedLlmResponseException -> "LLM_EVIDENCE_INVALID"
                is LlmUnavailableException -> "LLM_UNAVAILABLE"
                else -> throw error
            }
            // Persist the failure so reloads never pretend that a missing narrative is still generating.
            session.resultNarrativeJson = objectMapper.writeValueAsString(mapOf("failureCode" to code))
            sessionRepository.save(session)
            return DecisionResultResponse(DecisionStage.ANALYZED,
                buildResult(session, state, options, criteria, assessments, engineResult, null))
        }
        session.resultNarrativeJson = objectMapper.writeValueAsString(narrative)
        insightRepository.deleteAllInBatch(insightRepository.findBySessionOrderByPriorityAscIdAsc(session))
        insightRepository.saveAll(narrative.insights.map {
            DecisionInsightEntity(session, it.type, it.title, it.content, it.priority)
        })
        sessionRepository.save(session)
        return DecisionResultResponse(
            DecisionStage.ANALYZED,
            buildResult(session, state, options, criteria, assessments, engineResult, narrative),
        )
    }

    @Transactional(readOnly = true)
    fun getResult(sessionId: String): DecisionResultResponse {
        val session = findSession(sessionId)
        val state = readState(session)
        val options = optionRepository.findBySessionOrderByIdAsc(session)
        val criteria = criterionRepository.findBySessionOrderByIdAsc(session)
        if (session.stage != DecisionStage.ANALYZED || options.isEmpty() || criteria.isEmpty()) {
            throw DecisionResultNotFoundException(sessionId)
        }
        val assessments = scoreRepository.findByOptionSessionOrderByIdAsc(session)
        val engineResult = calculateFromEntities(options, criteria, assessments)
        return DecisionResultResponse(
            DecisionStage.ANALYZED,
            buildResult(session, state, options, criteria, assessments, engineResult, readNarrative(session)),
        )
    }

    private fun buildResult(
        session: DecisionSessionEntity,
        state: DecisionState,
        options: List<DecisionOptionEntity>,
        criteria: List<DecisionCriterionEntity>,
        assessments: List<CriterionOptionScoreEntity>,
        engineResult: DecisionEngineResult,
        narrative: GeneratedNarrative? = readNarrative(session),
    ): DecisionResult {
        val scoreByOption = engineResult.optionScores.associateBy { it.optionId }
        val fallback = fallbackGuidance(state, options)
        val guidance = narrative?.verdict?.let {
            fallback.copy(encouragedOptionId = it.recommendedOptionId,
                basis = if (it.recommendedOptionId == null) GuidanceBasis.NEEDS_VERIFICATION else GuidanceBasis.CONTEXTUAL,
                headline = it.headline, rationale = it.rationale, encouragement = it.encouragement, confidence = it.confidence.name,
                nextAction = it.nextAction, practicalAlternative = it.practicalAlternative, evidenceRefs = it.evidenceRefs)
        } ?: fallback
        val fallbackInsights = buildFallbackInsights(guidance)
        val resultInsights = narrative?.insights?.map {
            ResultInsight(it.type, it.title, it.content, it.priority, it.evidenceRefs, it.assumptionRefs, it.confidence.name, it.whatCouldChange)
        } ?: fallbackInsights

        return DecisionResult(
            sessionId = session.publicId,
            title = session.title,
            options = options.map {
                val score = scoreByOption.getValue(it.optionKey)
                ResultOption(it.optionKey, it.name, it.summary, score.score, score.rank, score.tiedForLead)
            },
            criteria = criteria.map { ResultCriterion(it.criterionKey, it.name, it.weight, it.sourceType) },
            assessments = assessments.map {
                ResultAssessment(it.criterion.criterionKey, it.option.optionKey, it.score, it.reason, it.sourceType)
            },
            insights = resultInsights,
            completeness = ResultCompleteness(
                engineResult.completeness.expectedScoreCount,
                engineResult.completeness.providedScoreCount,
                engineResult.completeness.missingScoreCount,
                engineResult.completeness.scoreCoveragePercent,
                engineResult.completeness.weightedCoveragePercent,
                engineResult.completeness.missingAssessments.map { MissingAssessmentDto(it.optionId, it.criterionId) },
            ),
            narrativeStatus = if (narrative != null) NarrativeStatus.READY else if (readNarrativeFailure(session) != null) NarrativeStatus.FALLBACK else NarrativeStatus.PENDING,
            narrativeError = readNarrativeFailure(session),
            guidance = guidance,
            optionProfiles = narrative?.optionProfiles
                ?.takeIf { profiles -> profiles.map { it.optionId }.toSet() == options.map { it.optionKey }.toSet() }
                ?.map { ResultOptionProfile(it.optionId, it.pros, it.cons, it.bestWhen, it.evidenceRefs) }
                ?: buildOptionProfiles(state.decisionTitle, options, criteria),
            scenarioForecasts = buildScenarioForecasts(options, criteria, assessments),
            evidenceQuality = buildEvidenceQuality(state, assessments),
            actionPlan = narrative?.actionPlan?.map {
                ResultActionStep(it.order, it.timing, it.task, it.why, it.evidenceNeeded, it.doneWhen)
            } ?: fallbackActions(state, guidance, options),
            decisionRules = narrative?.decisionRules?.map { ResultDecisionRule(it.outcome.name, it.condition) }
                ?: fallbackRules(guidance, options),
        )
    }

    private fun fallbackGuidance(
        state: DecisionState,
        options: List<DecisionOptionEntity>,
    ): ResultGuidance {
        val purchase = isPurchase(state.decisionTitle, options)
        val next = if (purchase) "구매로 해결하려는 문제와 이번 달 부담 가능한 총지출을 적어보세요."
            else "${state.missingInformation.firstOrNull() ?: "가장 중요한 조건의 실제 내용"}을 확인해보세요."
        return ResultGuidance(
            encouragedOptionId = null,
            basis = GuidanceBasis.NEEDS_VERIFICATION,
            headline = if (purchase) "구매 확정 전에, 실제 필요와 지출 부담부터 확인해보세요." else "선택을 확정하기 전에 핵심 조건을 확인해보세요.",
            rationale = "맞춤 AI 해석이 아직 완료되지 않았습니다. 입력한 선호 점수만으로 실제 장점이나 추천을 확정하지 않습니다.",
            encouragement = "확인할 조건 하나를 정하는 것도 결정을 향한 진전이에요.",
            confidence = "LOW",
            nextAction = next,
            practicalAlternative = if (purchase) "바로 구매하거나 완전히 포기하기 전에, 기존 물건으로 해결하거나 체험·대여가 가능한지 확인한 뒤 다시 결정할 수 있어요."
                else "바로 실행하는 대신 짧은 체험이나 정보 확인이 가능한지 살펴보고, 확인한 조건으로 다시 비교해보세요.",
        )
    }

    private fun isPurchase(title: String, options: List<DecisionOptionEntity>) =
        Regex("구매|살까|구입|쇼핑").containsMatchIn(title + options.joinToString { it.name })

    private fun buildOptionProfiles(
        title: String,
        options: List<DecisionOptionEntity>,
        criteria: List<DecisionCriterionEntity>,
    ): List<ResultOptionProfile> {
        return options.map { option ->
            val purchase = isPurchase(title, options)
            val defer = Regex("안\\s*(산|사|함|한다|구매)|않|보류|포기|유지|미루|미룸").containsMatchIn(option.name)
            val pros = when {
                purchase && defer -> listOf("지금 구매를 미루면 해당 구매 지출을 보류할 수 있어요.", "기존 물건이나 다른 해결 방법을 먼저 비교할 시간을 확보할 수 있어요.")
                purchase -> listOf("실제로 자주 쓸 용도가 있다면 필요한 기능을 바로 활용할 수 있어요.", "기존 방법의 불편을 해결하는 제품인지 확인되면 구매 목적이 분명해져요.")
                else -> listOf("${criteria.firstOrNull()?.name ?: "핵심 조건"}을 실제로 충족하는지 확인할 후보예요.", "작게 시험할 수 있다면 전면 실행 전에 적합성을 확인할 수 있어요.")
            }
            val cons = when {
                purchase && defer -> listOf("현재 꼭 필요한 용도가 있다면 그 필요가 해결되지 않을 수 있어요.", "대체 방법에도 시간이나 비용이 드는지 확인해야 해요.")
                purchase -> listOf("구매대금 외 유지비·부속품 비용까지 지출 한도 안인지 확인해야 해요.", "기대만큼 쓰지 않을 때의 부담과 반품 조건은 아직 확인되지 않았어요.")
                else -> listOf("실제 비용과 필요한 시간은 입력된 근거로 다시 확인해야 해요.", "예상과 다를 때 되돌릴 수 있는 조건을 확인해야 해요.")
            }
            ResultOptionProfile(option.optionKey, pros, cons, "기본 점검 안내이며 맞춤 AI 분석으로 검증된 결론은 아닙니다.")
        }
    }

    private fun buildScenarioForecasts(
        options: List<DecisionOptionEntity>,
        criteria: List<DecisionCriterionEntity>,
        assessments: List<CriterionOptionScoreEntity>,
    ): List<ResultScenarioForecast> {
        val scoreMap = assessments.associate { (it.option.optionKey to it.criterion.criterionKey) to it.score }
        return criteria.map { focus ->
            val current = focus.weight.multiply(BigDecimal("100")).setScale(0, RoundingMode.HALF_UP).toInt()
            val weights = (listOf(10, 20, 30, 40, 50, current)).distinct().sorted()
            val otherTotal = criteria.filterNot { it.criterionKey == focus.criterionKey }
                .fold(BigDecimal.ZERO) { total, item -> total + item.weight }
            val points = weights.map { percent ->
                val focusWeight = BigDecimal.valueOf(percent.toLong()).divide(BigDecimal("100"), 8, RoundingMode.HALF_UP)
                val remaining = BigDecimal.ONE.subtract(focusWeight)
                val adjusted = criteria.associate { criterion ->
                    criterion.criterionKey to when {
                        criterion.criterionKey == focus.criterionKey -> focusWeight
                        otherTotal.compareTo(BigDecimal.ZERO) == 0 -> remaining.divide(BigDecimal.valueOf((criteria.size - 1).coerceAtLeast(1).toLong()), 8, RoundingMode.HALF_UP)
                        else -> criterion.weight.divide(otherTotal, 8, RoundingMode.HALF_UP).multiply(remaining)
                    }
                }
                val scores = options.associate { option ->
                    val score = criteria.fold(BigDecimal.ZERO) { total, criterion ->
                        total + (scoreMap[option.optionKey to criterion.criterionKey] ?: BigDecimal.ZERO) * adjusted.getValue(criterion.criterionKey)
                    }.setScale(2, RoundingMode.HALF_UP)
                    option.optionKey to score
                }
                ResultScenarioPoint(percent, scores)
            }
            ResultScenarioForecast(focus.criterionKey, focus.name, BigDecimal.valueOf(current.toLong()), points)
        }
    }

    private fun buildEvidenceQuality(state: DecisionState, assessments: List<CriterionOptionScoreEntity>): ResultEvidenceQuality {
        val factCount = state.knownFacts.size + assessments.count { it.sourceType == DecisionSourceType.USER_FACT }
        val assumptionCount = state.assumptions.size + assessments.count { it.sourceType == DecisionSourceType.USER_ASSUMPTION }
        val inferenceCount = state.aiInferences.size + assessments.count { it.sourceType == DecisionSourceType.AI_INFERENCE }
        val reasoned = assessments.count { !it.reason.isNullOrBlank() }
        // Filling a score form is not independent verification of the underlying facts.
        val level = if (state.knownFacts.size >= 2 && state.missingInformation.isEmpty()) "MEDIUM" else "LOW"
        return ResultEvidenceQuality(level, factCount, assumptionCount, inferenceCount, reasoned)
    }

    private fun buildFallbackInsights(
        guidance: ResultGuidance,
    ): List<ResultInsight> {
        return listOf(
            ResultInsight(
                InsightType.KEY_DRIVER,
                "먼저 확인할 실제 조건",
                guidance.nextAction,
                1,
                emptyList(),
                emptyList(),
                "LOW",
                "실제 조건을 확인한 뒤에 추천 방향을 판단할 수 있어요.",
            ),
            ResultInsight(
                InsightType.TRADE_OFF,
                "바로 결정하기 어려울 때의 대안",
                guidance.practicalAlternative,
                2,
                emptyList(),
                emptyList(),
                "LOW",
                "대안의 실제 이용 가능 여부는 확인이 필요합니다.",
            ),
        )
    }

    private fun fallbackActions(
        state: DecisionState,
        guidance: ResultGuidance,
        options: List<DecisionOptionEntity>,
    ): List<ResultActionStep> {
        val name = options.firstOrNull { it.optionKey == guidance.encouragedOptionId }?.name ?: "각 선택지"
        val missing = state.missingInformation.firstOrNull() ?: "가장 중요한 기준의 실제 조건"
        return listOf(
            ResultActionStep(1, "오늘", guidance.nextAction, "기대 효과를 현실 자료로 확인하면 망설임이 줄어요.", listOf(missing), "확인한 근거를 한 문장으로 기록했을 때"),
            ResultActionStep(2, "확인 후", "${name}을 되돌릴 수 있는 작은 방식으로 시험할 수 있는지 알아보기", "큰 결정을 작은 실험으로 바꾸면 장점과 부담을 직접 느낄 수 있어요.", emptyList(), "가능한 체험 방법과 중단 조건을 확인했을 때"),
            ResultActionStep(3, "실험 후", "실제 효과와 감당할 부담으로 다시 비교하기", "근거와 마음이 같은 방향인지 확인하는 단계예요.", listOf("실험에서 확인한 장점과 부담"), "선택 또는 보류할 조건을 한 문장으로 정했을 때"),
        )
    }

    private fun fallbackRules(guidance: ResultGuidance, options: List<DecisionOptionEntity>): List<ResultDecisionRule> {
        val name = options.firstOrNull { it.optionKey == guidance.encouragedOptionId }?.name ?: "추천 방향"
        return listOf(
            ResultDecisionRule("SELECT", "${name}의 핵심 장점이 실제 확인되고 감정적 부담이 감당 가능한 수준일 때"),
            ResultDecisionRule("REASSESS", "실제 용도·비용·조건이 처음 기대와 다르다고 확인될 때"),
            ResultDecisionRule("PAUSE", "되돌리기 어려운 손실이나 안전 문제가 새로 확인될 때"),
        )
    }

    private fun calculate(state: DecisionState, assessments: List<EngineAssessment>) = engine.calculate(
        DecisionEngineInput(
            state.options.map { EngineOption(it.id) },
            state.criteria.map { EngineCriterion(it.id, it.weight) },
            assessments,
        ),
    )

    private fun calculateFromEntities(
        options: List<DecisionOptionEntity>,
        criteria: List<DecisionCriterionEntity>,
        assessments: List<CriterionOptionScoreEntity>,
    ) = engine.calculate(
        DecisionEngineInput(
            options.map { EngineOption(it.optionKey) },
            criteria.map { EngineCriterion(it.criterionKey, it.weight.toDouble()) },
            assessments.map { EngineAssessment(it.option.optionKey, it.criterion.criterionKey, it.score.toDouble()) },
        ),
    )

    private fun readNarrative(session: DecisionSessionEntity): GeneratedNarrative? = session.resultNarrativeJson
        ?.takeIf(String::isNotBlank)
        ?.takeUnless { objectMapper.readTree(it).has("failureCode") }
        ?.let { objectMapper.readValue(it, GeneratedNarrative::class.java) }

    private fun readNarrativeFailure(session: DecisionSessionEntity): String? = session.resultNarrativeJson
        ?.takeIf(String::isNotBlank)?.let { objectMapper.readTree(it).path("failureCode").asText("").ifBlank { null } }

    private fun clearPreviousResult(session: DecisionSessionEntity) {
        scoreRepository.deleteAllInBatch(scoreRepository.findByOptionSessionOrderByIdAsc(session))
        insightRepository.deleteAllInBatch(insightRepository.findBySessionOrderByPriorityAscIdAsc(session))
        criterionRepository.deleteAllInBatch(criterionRepository.findBySessionOrderByIdAsc(session))
        optionRepository.deleteAllInBatch(optionRepository.findBySessionOrderByIdAsc(session))
    }

    private fun findSession(sessionId: String): DecisionSessionEntity =
        sessionRepository.findByPublicId(sessionId) ?: throw DecisionSessionNotFoundException(sessionId)

    private fun readState(session: DecisionSessionEntity): DecisionState {
        val json = session.stateJson ?: throw DecisionNotReadyException(session.publicId)
        return objectMapper.readValue(json, DecisionState::class.java)
    }

    private fun decimal(value: Double) = BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP)
}

class DecisionNotReadyException(sessionId: String) : RuntimeException("Decision session '$sessionId' is not ready to analyze")
class DecisionResultNotFoundException(sessionId: String) : RuntimeException("Decision result for session '$sessionId' was not found")
