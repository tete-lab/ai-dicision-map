package com.aidecisionmap.decision.domain

import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class DecisionState(
    @field:NotBlank
    @field:Size(max = 200)
    val decisionTitle: String,
    @field:NotBlank
    @field:Size(max = 2_000)
    val summary: String,
    val stage: DecisionStage,
    @field:Valid
    @field:Size(max = 8)
    val options: List<DecisionOption>,
    @field:Valid
    @field:Size(max = 12)
    val criteria: List<DecisionCriterion>,
    @field:Size(max = 20)
    val knownFacts: List<@NotBlank @Size(max = 500) String>,
    @field:Size(max = 20)
    val assumptions: List<@NotBlank @Size(max = 500) String>,
    @field:Size(max = 20)
    val aiInferences: List<@NotBlank @Size(max = 500) String>,
    @field:Size(max = 12)
    val missingInformation: List<@NotBlank @Size(max = 300) String>,
    @field:Size(max = 12)
    val askedQuestions: List<@NotBlank @Size(max = 500) String> = emptyList(),
    @field:Size(max = 80)
    val userLeaningOptionId: String? = null,
    @field:Size(max = 8)
    val userLeaningEvidence: List<@NotBlank @Size(max = 300) String> = emptyList(),
    @field:Min(0)
    @field:Max(100)
    val progress: Int,
    val readyToAnalyze: Boolean,
    @field:Size(max = 500)
    val nextQuestion: String,
)

data class DecisionOption(
    @field:NotBlank
    @field:Size(max = 80)
    val id: String,
    @field:NotBlank
    @field:Size(max = 200)
    val name: String,
)

data class DecisionCriterion(
    @field:NotBlank
    @field:Size(max = 80)
    val id: String,
    @field:NotBlank
    @field:Size(max = 200)
    val name: String,
    @field:DecimalMin("0.0")
    @field:DecimalMax("1.0")
    val weight: Double,
    val sourceType: DecisionSourceType,
    @field:DecimalMin("0.0")
    @field:DecimalMax("1.0")
    val confidence: Double,
)

enum class DecisionSourceType {
    USER_FACT,
    USER_ASSUMPTION,
    AI_INFERENCE,
}

data class LlmDecisionTurn(
    @field:NotBlank
    @field:Size(max = 2_000)
    val assistantMessage: String,
    val suggestionMode: SuggestedAnswerMode,
    @field:Valid
    @field:Size(max = 4)
    val suggestedAnswers: List<SuggestedAnswer>,
    @field:Valid
    val state: DecisionState,
)

enum class SuggestedAnswerMode {
    SINGLE,
    ORDERED,
}

data class SuggestedAnswer(
    @field:NotBlank
    @field:Size(max = 80)
    val label: String,
    @field:NotBlank
    @field:Size(max = 300)
    val value: String,
)
