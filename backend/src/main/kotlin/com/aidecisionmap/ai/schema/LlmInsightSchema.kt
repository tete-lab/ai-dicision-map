package com.aidecisionmap.ai.schema

import com.aidecisionmap.ai.client.GeneratedNarrative
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.validation.Validator
import org.springframework.stereotype.Component

@Component
class LlmInsightJsonSchema(objectMapper: ObjectMapper) {
    val value: JsonNode = objectMapper.readTree(
        """
        {
          "type": "object",
          "additionalProperties": false,
          "required": ["verdict", "optionProfiles", "insights", "actionPlan", "decisionRules"],
          "properties": {
            "verdict": {
              "type": "object",
              "additionalProperties": false,
              "required": ["headline", "rationale", "encouragement", "confidence"],
              "properties": {
                "headline": {"type": "string"},
                "rationale": {"type": "string"},
                "encouragement": {"type": "string"},
                "confidence": {"type": "string", "enum": ["HIGH", "MEDIUM", "LOW"]}
              }
            },
            "optionProfiles": {
              "type": "array",
              "minItems": 2,
              "maxItems": 8,
              "items": {
                "type": "object",
                "additionalProperties": false,
                "required": ["optionId", "pros", "cons"],
                "properties": {
                  "optionId": {"type": "string"},
                  "pros": {"type": "array", "minItems": 2, "maxItems": 3, "items": {"type": "string"}},
                  "cons": {"type": "array", "minItems": 2, "maxItems": 3, "items": {"type": "string"}}
                }
              }
            },
            "insights": {
              "type": "array",
              "minItems": 2,
              "maxItems": 4,
              "items": {
                "type": "object",
                "additionalProperties": false,
                "required": ["type", "title", "content", "evidenceRefs", "assumptionRefs", "confidence", "whatCouldChange", "priority"],
                "properties": {
                  "type": {"type": "string", "enum": ["KEY_DRIVER", "TRADE_OFF", "RISK", "MISSING_INFORMATION"]},
                  "title": {"type": "string"},
                  "content": {"type": "string"},
                  "evidenceRefs": {"type": "array", "items": {"type": "string"}},
                  "assumptionRefs": {"type": "array", "items": {"type": "string"}},
                  "confidence": {"type": "string", "enum": ["HIGH", "MEDIUM", "LOW"]},
                  "whatCouldChange": {"type": "string"},
                  "priority": {"type": "integer"}
                }
              }
            },
            "actionPlan": {
              "type": "array",
              "minItems": 2,
              "maxItems": 5,
              "items": {
                "type": "object",
                "additionalProperties": false,
                "required": ["order", "timing", "task", "why", "evidenceNeeded", "doneWhen"],
                "properties": {
                  "order": {"type": "integer"},
                  "timing": {"type": "string"},
                  "task": {"type": "string"},
                  "why": {"type": "string"},
                  "evidenceNeeded": {"type": "array", "items": {"type": "string"}},
                  "doneWhen": {"type": "string"}
                }
              }
            },
            "decisionRules": {
              "type": "array",
              "minItems": 1,
              "maxItems": 3,
              "items": {
                "type": "object",
                "additionalProperties": false,
                "required": ["outcome", "condition"],
                "properties": {
                  "outcome": {"type": "string", "enum": ["SELECT", "REASSESS", "PAUSE"]},
                  "condition": {"type": "string"}
                }
              }
            }
          }
        }
        """.trimIndent(),
    )
}

@Component
class LlmInsightParser(
    objectMapper: ObjectMapper,
    private val validator: Validator,
) {
    private val strictObjectMapper = objectMapper.copy()
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)

    fun parse(json: String): GeneratedNarrative {
        val response = try {
            strictObjectMapper.readValue(json, GeneratedNarrative::class.java)
        } catch (exception: Exception) {
            throw MalformedLlmResponseException("AI narrative response was not valid JSON", exception)
        }
        val violations = validator.validate(response)
        if (violations.isNotEmpty()) {
            val details = violations.map { "${it.propertyPath}: ${it.message}" }.sorted().joinToString("; ")
            throw MalformedLlmResponseException("AI narrative response failed validation: $details")
        }
        if (response.insights.size !in 2..4 || response.actionPlan.size !in 2..5 || response.decisionRules.size !in 1..3) {
            throw MalformedLlmResponseException("AI narrative response contained an invalid item count")
        }
        if (response.insights.map { it.priority }.distinct().size != response.insights.size) {
            throw MalformedLlmResponseException("AI insight priorities must be unique")
        }
        if (response.optionProfiles.map { it.optionId }.distinct().size != response.optionProfiles.size ||
            response.optionProfiles.any { it.pros.size !in 2..3 || it.cons.size !in 2..3 }
        ) {
            throw MalformedLlmResponseException("AI option profiles must contain unique options with concrete pros and cons")
        }
        if (response.actionPlan.map { it.order }.distinct().size != response.actionPlan.size) {
            throw MalformedLlmResponseException("AI action order values must be unique")
        }
        return response.copy(
            insights = response.insights.sortedBy { it.priority },
            actionPlan = response.actionPlan.sortedBy { it.order },
        )
    }
}
