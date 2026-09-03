package com.aidecisionmap.ai.client

import com.aidecisionmap.conversation.domain.MessageRole
import com.aidecisionmap.decision.domain.DecisionState
import com.aidecisionmap.decision.domain.LlmDecisionTurn
import com.aidecisionmap.decision.result.InsightType
import java.math.BigDecimal

interface LlmClient {
    fun generateTurn(request: LlmTurnRequest): LlmDecisionTurn
    fun generateNarrative(request: LlmNarrativeRequest): GeneratedNarrative
}

data class LlmTurnRequest(
    val messages: List<TranscriptMessage>,
    val previousState: DecisionState?,
)

data class TranscriptMessage(
    val role: MessageRole,
    val content: String,
)

data class LlmNarrativeRequest(
    val decisionTitle: String,
    val options: List<InsightOptionInput>,
    val criteria: List<InsightCriterionInput>,
    val assessments: List<InsightAssessmentInput>,
    val completenessPercent: BigDecimal,
    val encouragedOptionId: String?,
    val encouragementBasis: String,
    val userLeaningOptionId: String?,
    val userLeaningEvidence: List<String>,
    val knownFacts: List<String>,
    val assumptions: List<String>,
    val aiInferences: List<String>,
    val missingInformation: List<String>,
)

data class InsightOptionInput(val id: String, val name: String, val score: BigDecimal?)
data class InsightCriterionInput(val id: String, val name: String, val normalizedWeight: BigDecimal)
data class InsightAssessmentInput(
    val optionId: String,
    val criterionId: String,
    val score: BigDecimal,
    val reason: String?,
    val sourceType: String = "USER_ASSUMPTION",
)

data class GeneratedInsight(
    val type: InsightType,
    val title: String,
    val content: String,
    val evidenceRefs: List<String>,
    val assumptionRefs: List<String>,
    val confidence: NarrativeConfidence,
    val whatCouldChange: String,
    val priority: Int,
)

data class GeneratedNarrative(
    val verdict: GeneratedVerdict,
    val optionProfiles: List<GeneratedOptionProfile>,
    val insights: List<GeneratedInsight>,
    val actionPlan: List<GeneratedActionStep>,
    val decisionRules: List<GeneratedDecisionRule>,
)

data class GeneratedOptionProfile(
    val optionId: String,
    val pros: List<String>,
    val cons: List<String>,
    val bestWhen: String = "",
    val evidenceRefs: List<String> = emptyList(),
)

data class GeneratedVerdict(
    val headline: String,
    val rationale: String,
    val encouragement: String,
    val confidence: NarrativeConfidence,
    val recommendedOptionId: String? = null,
    val nextAction: String = "",
    val practicalAlternative: String = "",
    val evidenceRefs: List<String> = emptyList(),
)

data class GeneratedActionStep(
    val order: Int,
    val timing: String,
    val task: String,
    val why: String,
    val evidenceNeeded: List<String>,
    val doneWhen: String,
)

data class GeneratedDecisionRule(
    val outcome: DecisionRuleOutcome,
    val condition: String,
)

enum class NarrativeConfidence { HIGH, MEDIUM, LOW }
enum class DecisionRuleOutcome { SELECT, REASSESS, PAUSE }
