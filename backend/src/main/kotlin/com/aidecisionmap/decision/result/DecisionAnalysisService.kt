package com.aidecisionmap.decision.result

import com.aidecisionmap.ai.client.GeneratedNarrative
import com.aidecisionmap.ai.client.InsightAssessmentInput
import com.aidecisionmap.ai.client.InsightCriterionInput
import com.aidecisionmap.ai.client.InsightOptionInput
import com.aidecisionmap.ai.client.LlmClient
import com.aidecisionmap.ai.client.LlmNarrativeRequest
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
        val guidance = fallbackGuidance(state, options, engineResult)
        val scoreByOption = engineResult.optionScores.associateBy { it.optionId }
        val narrative = llmClient.generateNarrative(
            LlmNarrativeRequest(
                decisionTitle = state.decisionTitle,
                options = options.map { InsightOptionInput(it.optionKey, it.name, scoreByOption.getValue(it.optionKey).score) },
                criteria = criteria.map { InsightCriterionInput(it.criterionKey, it.name, it.weight) },
                assessments = assessments.map {
                    InsightAssessmentInput(it.option.optionKey, it.criterion.criterionKey, it.score, it.reason)
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
            ),
        )
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
        val fallback = fallbackGuidance(state, options, engineResult)
        val guidance = narrative?.verdict?.let {
            fallback.copy(headline = it.headline, rationale = it.rationale, encouragement = it.encouragement, confidence = it.confidence.name)
        } ?: fallback
        val fallbackInsights = buildFallbackInsights(guidance, options, criteria, assessments)
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
            narrativeStatus = if (narrative == null) NarrativeStatus.PENDING else NarrativeStatus.READY,
            guidance = guidance,
            optionProfiles = narrative?.optionProfiles
                ?.takeIf { profiles -> profiles.map { it.optionId }.toSet() == options.map { it.optionKey }.toSet() }
                ?.map { ResultOptionProfile(it.optionId, it.pros, it.cons) }
                ?: buildOptionProfiles(options, criteria, assessments),
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
        result: DecisionEngineResult,
    ): ResultGuidance {
        val scored = result.optionScores.filter { it.score != null }.sortedByDescending { it.score }
        val leader = scored.firstOrNull()
        val leaning = scored.firstOrNull { it.optionId == state.userLeaningOptionId }
        val leaningSupported = leader?.score != null && leaning?.score != null &&
            leader.score.subtract(leaning.score).abs() <= BigDecimal("8.00")
        val encouraged = if (leaningSupported) leaning else leader
        val optionName = options.firstOrNull { it.optionKey == encouraged?.optionId }?.name ?: "상위 선택지"
        val basis = when {
            scored.size > 1 && scored[0].score?.compareTo(scored[1].score) == 0 -> GuidanceBasis.TIE
            leaningSupported -> GuidanceBasis.USER_LEANING_SUPPORTED
            else -> GuidanceBasis.SCORE_LEADER
        }
        val headline = when (basis) {
            GuidanceBasis.USER_LEANING_SUPPORTED -> "$optionName 쪽으로 마음을 옮겨도 좋아 보여요."
            GuidanceBasis.TIE -> "점수는 팽팽하지만, 마음이 편해지는 방향을 시험해볼 때예요."
            GuidanceBasis.SCORE_LEADER -> "$optionName 쪽이 현재 기준에서 한 걸음 앞서 있어요."
        }
        return ResultGuidance(
            encouragedOptionId = encouraged?.optionId,
            basis = basis,
            headline = headline,
            rationale = if (basis == GuidanceBasis.USER_LEANING_SUPPORTED) {
                "점수 차이가 크지 않고 대화에서 드러난 선호도 같은 방향을 가리켜요."
            } else {
                "사용자가 확인한 기준별 점수와 가중치를 합산한 결과예요."
            },
            encouragement = "$optionName 선택이 만들 변화를 작은 실행으로 먼저 확인해보세요. 확신은 행동 뒤에 더 선명해질 수 있어요.",
            confidence = if (result.completeness.weightedCoveragePercent >= BigDecimal("80")) "MEDIUM" else "LOW",
        )
    }

    private fun buildOptionProfiles(
        options: List<DecisionOptionEntity>,
        criteria: List<DecisionCriterionEntity>,
        assessments: List<CriterionOptionScoreEntity>,
    ): List<ResultOptionProfile> {
        val criterionNames = criteria.associate { it.criterionKey to it.name }
        return options.map { option ->
            val values = assessments.filter { it.option.optionKey == option.optionKey }
            val comparisons = values.map { assessment ->
                val alternatives = assessments.filter {
                    it.criterion.criterionKey == assessment.criterion.criterionKey && it.option.optionKey != option.optionKey
                }
                val comparison = alternatives.map { it.score }.takeIf { it.isNotEmpty() }
                    ?.reduce(BigDecimal::add)?.divide(BigDecimal.valueOf(alternatives.size.toLong()), 2, RoundingMode.HALF_UP)
                    ?: BigDecimal("50")
                assessment to assessment.score.subtract(comparison)
            }
            val favorable = comparisons.filter { it.second >= BigDecimal.ZERO }.sortedByDescending { it.second }
            val pros = (favorable.ifEmpty { comparisons.sortedByDescending { it.first.score }.take(2) }).take(3).map { (assessment, difference) ->
                val name = criterionNames[assessment.criterion.criterionKey] ?: "핵심 기준"
                if (difference >= BigDecimal.ZERO) {
                    "${name}에서 다른 대안보다 ${difference.setScale(0, RoundingMode.HALF_UP)}점 유리해 기대 효과가 큽니다. ${assessment.reason.orEmpty()}".trim()
                } else {
                    "$name 점수 ${assessment.score.setScale(0, RoundingMode.HALF_UP)}점은 이 대안에서 살릴 수 있는 장점입니다. ${assessment.reason.orEmpty()}".trim()
                }
            }
            val unfavorable = comparisons.filter { it.second < BigDecimal.ZERO }.sortedBy { it.second }
            val cons = (unfavorable.ifEmpty { comparisons.sortedBy { it.first.score }.take(2) }).take(3).map { (assessment, difference) ->
                val name = criterionNames[assessment.criterion.criterionKey] ?: "핵심 기준"
                if (difference < BigDecimal.ZERO) {
                    "${name}에서 다른 대안보다 ${difference.abs().setScale(0, RoundingMode.HALF_UP)}점 불리할 수 있어 실제 조건 확인이 필요합니다. ${assessment.reason.orEmpty()}".trim()
                } else {
                    "${name}은 상대 우위가 있어도 ${assessment.score.setScale(0, RoundingMode.HALF_UP)}점 수준이므로 기대치를 현실과 대조해야 합니다. ${assessment.reason.orEmpty()}".trim()
                }
            }
            ResultOptionProfile(option.optionKey, pros, cons)
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
        val level = when {
            factCount >= assumptionCount && reasoned == assessments.size -> "HIGH"
            factCount > 0 || reasoned >= assessments.size / 2 -> "MEDIUM"
            else -> "LOW"
        }
        return ResultEvidenceQuality(level, factCount, assumptionCount, inferenceCount, reasoned)
    }

    private fun buildFallbackInsights(
        guidance: ResultGuidance,
        options: List<DecisionOptionEntity>,
        criteria: List<DecisionCriterionEntity>,
        assessments: List<CriterionOptionScoreEntity>,
    ): List<ResultInsight> {
        val encouraged = options.firstOrNull { it.optionKey == guidance.encouragedOptionId }
        val strongest = assessments.filter { it.option.optionKey == encouraged?.optionKey }.maxByOrNull { it.score }
        val criterion = criteria.firstOrNull { it.criterionKey == strongest?.criterion?.criterionKey }
        return listOf(
            ResultInsight(
                InsightType.KEY_DRIVER,
                "지금 가장 힘을 주는 기준",
                "${criterion?.name ?: "상위 기준"}에서 ${encouraged?.name ?: "추천 방향"}의 평가가 상대적으로 좋아요.",
                1,
                strongest?.reason?.let(::listOf) ?: emptyList(),
                emptyList(),
                "MEDIUM",
                "해당 기준의 실제 조건이 달라지면 순위도 바뀔 수 있어요.",
            ),
            ResultInsight(
                InsightType.TRADE_OFF,
                "장점은 살리고 부담은 작게 시험하세요",
                "추천 방향의 장점을 먼저 작은 행동으로 검증하면 결정 부담을 줄일 수 있어요.",
                2,
                emptyList(),
                listOf("입력한 점수가 실제 조건을 반영한다는 가정"),
                "LOW",
                "현실 자료와 점수를 대조하면 판단이 더 분명해져요.",
            ),
        )
    }

    private fun fallbackActions(
        state: DecisionState,
        guidance: ResultGuidance,
        options: List<DecisionOptionEntity>,
    ): List<ResultActionStep> {
        val name = options.firstOrNull { it.optionKey == guidance.encouragedOptionId }?.name ?: "추천 선택지"
        val missing = state.missingInformation.firstOrNull() ?: "가장 중요한 기준의 실제 조건"
        return listOf(
            ResultActionStep(1, "오늘", "${name}의 실제 조건 한 가지 확인하기", "기대 효과를 현실 자료로 확인하면 망설임이 줄어요.", listOf(missing), "확인한 근거를 한 문장으로 기록했을 때"),
            ResultActionStep(2, "이번 주", "${name}을 되돌릴 수 있는 작은 방식으로 시험하기", "큰 결정을 작은 실험으로 바꾸면 장점과 부담을 직접 느낄 수 있어요.", emptyList(), "해본 뒤 만족·불안 점수를 각각 10점 만점으로 남겼을 때"),
            ResultActionStep(3, "실험 후", "점수와 마음의 변화를 다시 비교하기", "근거와 감정이 같은 방향인지 확인하는 마지막 단계예요.", listOf("실험 전후 점수"), "선택 또는 재검토 조건 중 하나가 충족됐을 때"),
        )
    }

    private fun fallbackRules(guidance: ResultGuidance, options: List<DecisionOptionEntity>): List<ResultDecisionRule> {
        val name = options.firstOrNull { it.optionKey == guidance.encouragedOptionId }?.name ?: "추천 방향"
        return listOf(
            ResultDecisionRule("SELECT", "${name}의 핵심 장점이 실제 확인되고 감정적 부담이 감당 가능한 수준일 때"),
            ResultDecisionRule("REASSESS", "가장 중요한 기준의 실제 점수가 예상보다 10점 이상 낮을 때"),
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
        ?.let { objectMapper.readValue(it, GeneratedNarrative::class.java) }

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
