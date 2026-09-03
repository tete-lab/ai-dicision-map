package com.aidecisionmap.decision.api

import com.aidecisionmap.decision.domain.DecisionState
import com.aidecisionmap.decision.domain.SuggestedAnswer
import com.aidecisionmap.decision.domain.SuggestedAnswerMode
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class DecisionMessageRequest(
    @field:NotBlank(message = "메시지를 입력해주세요.")
    @field:Size(max = 2_000, message = "메시지는 2,000자 이하여야 합니다.")
    val message: String,
)

data class DecisionTurnResponse(
    val sessionId: String,
    val assistantMessage: String,
    val suggestionMode: SuggestedAnswerMode,
    val suggestedAnswers: List<SuggestedAnswer>,
    val state: DecisionState,
    val responseMode: String = "AI",
)

data class DecisionStateResponse(
    val sessionId: String,
    val state: DecisionState,
)
