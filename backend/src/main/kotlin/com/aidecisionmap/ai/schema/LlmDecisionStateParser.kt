package com.aidecisionmap.ai.schema

import com.aidecisionmap.decision.domain.LlmDecisionTurn
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.validation.Validator
import org.springframework.stereotype.Component

@Component
class LlmDecisionStateParser(
    objectMapper: ObjectMapper,
    private val validator: Validator,
) {
    private val strictObjectMapper = objectMapper.copy()
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)

    fun parse(json: String): LlmDecisionTurn {
        val turn = try {
            strictObjectMapper.readValue(json, LlmDecisionTurn::class.java)
        } catch (exception: Exception) {
            throw MalformedLlmResponseException("AI response was not valid decision-state JSON", exception)
        }

        val violations = validator.validate(turn)
        if (violations.isNotEmpty()) {
            val details = violations.map { "${it.propertyPath}: ${it.message}" }.sorted().joinToString("; ")
            throw MalformedLlmResponseException("AI response failed validation: $details")
        }

        validateBusinessRules(turn)
        return turn
    }

    private fun validateBusinessRules(turn: LlmDecisionTurn) {
        val state = turn.state
        if (!state.readyToAnalyze && turn.suggestedAnswers.size != 4) {
            throw MalformedLlmResponseException("A non-ready turn must include exactly four suggested answers")
        }
        if (state.readyToAnalyze && turn.suggestedAnswers.isNotEmpty()) {
            throw MalformedLlmResponseException("A ready turn must not include suggested answers")
        }
        if (turn.suggestedAnswers.map { it.label }.distinct().size != turn.suggestedAnswers.size ||
            turn.suggestedAnswers.map { it.value }.distinct().size != turn.suggestedAnswers.size
        ) {
            throw MalformedLlmResponseException("Suggested answers must be unique")
        }
        if (turn.suggestedAnswers.any { it.value != "__custom__" && !KOREAN_TEXT.containsMatchIn(it.value) }) {
            throw MalformedLlmResponseException("Suggested answer values must be natural Korean text, not internal ids")
        }
        if (state.askedQuestions.map(::normalizeQuestion).distinct().size != state.askedQuestions.size) {
            throw MalformedLlmResponseException("Asked questions must not contain exact duplicates")
        }
        if (state.options.map { it.id }.distinct().size != state.options.size) {
            throw MalformedLlmResponseException("AI response contains duplicate option ids")
        }
        if (state.criteria.map { it.id }.distinct().size != state.criteria.size) {
            throw MalformedLlmResponseException("AI response contains duplicate criterion ids")
        }
        if (state.userLeaningOptionId != null && state.options.none { it.id == state.userLeaningOptionId }) {
            throw MalformedLlmResponseException("User leaning must reference an existing option id")
        }
        if (state.userLeaningOptionId == null && state.userLeaningEvidence.isNotEmpty()) {
            throw MalformedLlmResponseException("User leaning evidence requires a leaning option")
        }
        if (!state.readyToAnalyze && state.nextQuestion.isBlank()) {
            throw MalformedLlmResponseException("A non-ready state must include the next question")
        }
        if (state.readyToAnalyze && (state.options.size < 2 || state.criteria.size < 2)) {
            throw MalformedLlmResponseException("A ready state requires at least two options and two criteria")
        }
    }

    private fun normalizeQuestion(value: String) = value.lowercase().replace(NON_WORD, "")

    private companion object {
        val KOREAN_TEXT = Regex("[가-힣]")
        val NON_WORD = Regex("[^가-힣a-z0-9]")
    }
}

class MalformedLlmResponseException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
