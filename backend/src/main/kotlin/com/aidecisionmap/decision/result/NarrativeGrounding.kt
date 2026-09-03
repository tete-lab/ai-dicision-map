package com.aidecisionmap.decision.result

import com.aidecisionmap.ai.client.GeneratedNarrative
import com.aidecisionmap.ai.client.LlmNarrativeRequest
import com.aidecisionmap.ai.client.NarrativeConfidence
import com.aidecisionmap.ai.schema.MalformedLlmResponseException

/** Referential validation is a minimum safeguard, not proof that a claim is true. */
internal object NarrativeGrounding {
    fun validate(narrative: GeneratedNarrative, request: LlmNarrativeRequest) {
        val ids = request.options.map { it.id }.toSet()
        val references = (request.knownFacts + request.userLeaningEvidence).toSet()
        val assumptions = (request.assumptions + request.aiInferences).toSet()
        val evidence = narrative.verdict.evidenceRefs + narrative.optionProfiles.flatMap { it.evidenceRefs } + narrative.insights.flatMap { it.evidenceRefs }
        if (narrative.optionProfiles.map { it.optionId }.toSet() != ids || narrative.optionProfiles.size != ids.size ||
            (narrative.verdict.recommendedOptionId != null && narrative.verdict.recommendedOptionId !in ids) ||
            evidence.any { it !in references } || narrative.insights.flatMap { it.assumptionRefs }.any { it !in assumptions }) {
            throw MalformedLlmResponseException("Narrative contains unknown alternatives or unsupported evidence references")
        }
        if (narrative.verdict.recommendedOptionId != null && narrative.verdict.evidenceRefs.isEmpty()) {
            throw MalformedLlmResponseException("A recommendation requires user-context evidence")
        }
        if (narrative.verdict.evidenceRefs.isEmpty() && narrative.verdict.confidence != NarrativeConfidence.LOW) {
            throw MalformedLlmResponseException("Unsupported confidence in narrative")
        }
    }
}
