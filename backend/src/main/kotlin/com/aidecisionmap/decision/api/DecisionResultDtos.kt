package com.aidecisionmap.decision.api

import com.aidecisionmap.decision.domain.DecisionSourceType
import com.aidecisionmap.decision.domain.DecisionStage
import com.aidecisionmap.decision.result.InsightType
import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.math.BigDecimal

data class AnalyzeDecisionRequest(
    @field:Valid
    @field:Size(max = 96)
    val assessments: List<CriterionOptionAssessmentRequest>,
)

data class CriterionOptionAssessmentRequest(
    @field:NotBlank
    @field:Size(max = 100)
    val criterionId: String,
    @field:NotBlank
    @field:Size(max = 100)
    val optionId: String,
    @field:DecimalMin("0.0")
    @field:DecimalMax("100.0")
    val score: Double,
    @field:Size(max = 1_000)
    val reason: String? = null,
    val sourceType: DecisionSourceType,
)

data class DecisionResultResponse(
    val status: DecisionStage,
    val result: DecisionResult,
)

data class DecisionResult(
    val sessionId: String,
    val title: String,
    val options: List<ResultOption>,
    val criteria: List<ResultCriterion>,
    val assessments: List<ResultAssessment>,
    val insights: List<ResultInsight>,
    val completeness: ResultCompleteness,
    val narrativeStatus: NarrativeStatus,
    val guidance: ResultGuidance,
    val optionProfiles: List<ResultOptionProfile>,
    val scenarioForecasts: List<ResultScenarioForecast>,
    val evidenceQuality: ResultEvidenceQuality,
    val actionPlan: List<ResultActionStep>,
    val decisionRules: List<ResultDecisionRule>,
    val narrativeError: String? = null,
)

enum class NarrativeStatus { PENDING, READY, FALLBACK }
enum class GuidanceBasis { USER_LEANING_SUPPORTED, SCORE_LEADER, TIE, CONTEXTUAL, NEEDS_VERIFICATION }

data class ResultGuidance(
    val encouragedOptionId: String?,
    val basis: GuidanceBasis,
    val headline: String,
    val rationale: String,
    val encouragement: String,
    val confidence: String,
    val nextAction: String = "",
    val practicalAlternative: String = "",
    val evidenceRefs: List<String> = emptyList(),
)

data class ResultOptionProfile(
    val optionId: String,
    val pros: List<String>,
    val cons: List<String>,
    val bestWhen: String = "",
    val evidenceRefs: List<String> = emptyList(),
)

data class ResultScenarioForecast(
    val criterionId: String,
    val criterionName: String,
    val currentWeightPercent: BigDecimal,
    val points: List<ResultScenarioPoint>,
)

data class ResultScenarioPoint(
    val weightPercent: Int,
    val optionScores: Map<String, BigDecimal>,
)

data class ResultEvidenceQuality(
    val level: String,
    val factCount: Int,
    val assumptionCount: Int,
    val inferenceCount: Int,
    val reasonedAssessmentCount: Int,
)

data class ResultActionStep(
    val order: Int,
    val timing: String,
    val task: String,
    val why: String,
    val evidenceNeeded: List<String>,
    val doneWhen: String,
)

data class ResultDecisionRule(
    val outcome: String,
    val condition: String,
)

data class ResultOption(
    val id: String,
    val name: String,
    val summary: String?,
    val score: BigDecimal?,
    val rank: Int?,
    val tiedForLead: Boolean,
)

data class ResultCriterion(
    val id: String,
    val name: String,
    val normalizedWeight: BigDecimal,
    val sourceType: DecisionSourceType,
)

data class ResultAssessment(
    val criterionId: String,
    val optionId: String,
    val score: BigDecimal,
    val reason: String?,
    val sourceType: DecisionSourceType,
)

data class ResultInsight(
    val type: InsightType,
    val title: String,
    val content: String,
    val priority: Int,
    val evidenceRefs: List<String> = emptyList(),
    val assumptionRefs: List<String> = emptyList(),
    val confidence: String = "LOW",
    val whatCouldChange: String = "추가 근거가 확인되면 달라질 수 있어요.",
)

data class ResultCompleteness(
    val expectedScoreCount: Int,
    val providedScoreCount: Int,
    val missingScoreCount: Int,
    val scoreCoveragePercent: BigDecimal,
    val weightedCoveragePercent: BigDecimal,
    val missingAssessments: List<MissingAssessmentDto>,
)

data class MissingAssessmentDto(
    val optionId: String,
    val criterionId: String,
)
