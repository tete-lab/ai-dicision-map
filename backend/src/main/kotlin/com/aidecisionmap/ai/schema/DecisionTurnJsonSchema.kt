package com.aidecisionmap.ai.schema

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component

@Component
class DecisionTurnJsonSchema(objectMapper: ObjectMapper) {
    val value: JsonNode = objectMapper.readTree(SCHEMA)

    private companion object {
        val SCHEMA = """
            {
              "type": "object",
              "additionalProperties": false,
              "required": ["assistantMessage", "suggestionMode", "suggestedAnswers", "state"],
              "properties": {
                "assistantMessage": {"type": "string"},
                "suggestionMode": {"type": "string", "enum": ["SINGLE", "ORDERED"]},
                "suggestedAnswers": {
                  "type": "array",
                  "maxItems": 4,
                  "items": {
                    "type": "object",
                    "additionalProperties": false,
                    "required": ["label", "value"],
                    "properties": {
                      "label": {"type": "string"},
                      "value": {"type": "string"}
                    }
                  }
                },
                "state": {
                  "type": "object",
                  "additionalProperties": false,
                  "required": ["decisionTitle", "summary", "stage", "options", "criteria", "knownFacts", "assumptions", "aiInferences", "missingInformation", "askedQuestions", "userLeaningOptionId", "userLeaningEvidence", "progress", "readyToAnalyze", "nextQuestion"],
                  "properties": {
                    "decisionTitle": {"type": "string"},
                    "summary": {"type": "string"},
                    "stage": {"type": "string", "enum": ["STARTED", "IDENTIFYING_OPTIONS", "COLLECTING_CRITERIA", "COLLECTING_INFORMATION", "READY_TO_ANALYZE"]},
                    "options": {
                      "type": "array",
                      "items": {
                        "type": "object",
                        "additionalProperties": false,
                        "required": ["id", "name"],
                        "properties": {
                          "id": {"type": "string"},
                          "name": {"type": "string"}
                        }
                      }
                    },
                    "criteria": {
                      "type": "array",
                      "items": {
                        "type": "object",
                        "additionalProperties": false,
                        "required": ["id", "name", "weight", "sourceType", "confidence"],
                        "properties": {
                          "id": {"type": "string"},
                          "name": {"type": "string"},
                          "weight": {"type": "number"},
                          "sourceType": {"type": "string", "enum": ["USER_FACT", "USER_ASSUMPTION", "AI_INFERENCE"]},
                          "confidence": {"type": "number"}
                        }
                      }
                    },
                    "knownFacts": {"type": "array", "items": {"type": "string"}},
                    "assumptions": {"type": "array", "items": {"type": "string"}},
                    "aiInferences": {"type": "array", "items": {"type": "string"}},
                    "missingInformation": {"type": "array", "items": {"type": "string"}},
                    "askedQuestions": {"type": "array", "maxItems": 12, "items": {"type": "string"}},
                    "userLeaningOptionId": {"type": ["string", "null"]},
                    "userLeaningEvidence": {"type": "array", "items": {"type": "string"}},
                    "progress": {"type": "integer"},
                    "readyToAnalyze": {"type": "boolean"},
                    "nextQuestion": {"type": "string"}
                  }
                }
              }
            }
        """.trimIndent()
    }
}
