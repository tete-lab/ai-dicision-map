package com.aidecisionmap.ai.client

import com.aidecisionmap.ai.schema.DecisionTurnJsonSchema
import com.aidecisionmap.ai.schema.LlmDecisionStateParser
import com.aidecisionmap.ai.schema.LlmInsightJsonSchema
import com.aidecisionmap.ai.schema.LlmInsightParser
import com.aidecisionmap.ai.schema.MalformedLlmResponseException
import com.aidecisionmap.conversation.domain.MessageRole
import com.aidecisionmap.decision.domain.LlmDecisionTurn
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException

@Component
@ConditionalOnProperty(prefix = "app.llm", name = ["provider"], havingValue = "openai", matchIfMissing = true)
class OpenAiLlmClient(
    restClientBuilder: RestClient.Builder,
    private val properties: LlmProperties,
    private val objectMapper: ObjectMapper,
    private val parser: LlmDecisionStateParser,
    schema: DecisionTurnJsonSchema,
    private val insightParser: LlmInsightParser,
    insightSchema: LlmInsightJsonSchema,
) : LlmClient {
    private val decisionSchema = schema.value
    private val decisionInsightSchema = insightSchema.value
    private val restClient = restClientBuilder.baseUrl(properties.baseUrl.trimEnd('/')).build()

    override fun generateTurn(request: LlmTurnRequest): LlmDecisionTurn {
        ensureConfigured()
        return try {
            executeTurn(request)
        } catch (exception: RuntimeException) {
            throw normalize(exception)
        }
    }

    override fun generateNarrative(request: LlmNarrativeRequest): GeneratedNarrative {
        ensureConfigured()
        return withRetry { executeNarrative(request) }
    }

    private fun ensureConfigured() {
        if (properties.apiKey.isBlank() || properties.apiKey.startsWith("replace-with-") || properties.apiKey == "YOUR_API_KEY") {
            throw LlmConfigurationException("LLM_API_KEY is not configured")
        }
    }

    private fun <T> withRetry(operation: () -> T): T {
        var lastFailure: RuntimeException? = null
        repeat(2) { attempt ->
            try {
                return operation()
            } catch (exception: RuntimeException) {
                lastFailure = exception
                if (attempt == 1 || !isRetryable(exception)) {
                    throw normalize(exception)
                }
            }
        }
        throw LlmUnavailableException("The AI provider did not return a usable response", lastFailure)
    }

    private fun executeTurn(request: LlmTurnRequest): LlmDecisionTurn =
        parser.parse(executeRequest(buildConversationRequest(request)))

    private fun executeNarrative(request: LlmNarrativeRequest): GeneratedNarrative =
        insightParser.parse(executeRequest(buildInsightRequest(request)))

    private fun buildConversationRequest(request: LlmTurnRequest): Map<String, Any> {
        val latestUserMessage = request.messages.lastOrNull { it.role == MessageRole.USER }?.content.orEmpty()

        return mapOf(
            "model" to properties.conversationModel,
            "instructions" to SYSTEM_PROMPT,
            "input" to objectMapper.writeValueAsString(
                mapOf("currentState" to request.previousState, "latestUserMessage" to latestUserMessage),
            ),
            "reasoning" to mapOf("effort" to properties.conversationReasoningEffort),
            "text" to mapOf(
                "verbosity" to "low",
                "format" to mapOf(
                    "type" to "json_schema",
                    "name" to "decision_conversation_turn",
                    "description" to "One conversational reply and the complete current decision state",
                    "strict" to true,
                    "schema" to decisionSchema,
                ),
            ),
            "max_output_tokens" to properties.conversationMaxOutputTokens,
            "store" to false,
            "prompt_cache_key" to properties.promptVersion,
            "metadata" to mapOf("prompt_version" to properties.promptVersion),
        )
    }

    private fun buildInsightRequest(request: LlmNarrativeRequest): Map<String, Any> = mapOf(
        "model" to properties.narrativeModel,
        "instructions" to INSIGHT_PROMPT,
        "input" to objectMapper.writeValueAsString(request),
        "reasoning" to mapOf("effort" to properties.narrativeReasoningEffort),
        "text" to mapOf(
            "verbosity" to "medium",
            "format" to mapOf(
                "type" to "json_schema",
                    "name" to "decision_result_narrative",
                    "description" to "Context-grounded alternatives, tradeoffs and actionable decision advice",
                "strict" to true,
                "schema" to decisionInsightSchema,
            ),
        ),
        "max_output_tokens" to properties.narrativeMaxOutputTokens,
        "store" to false,
        "prompt_cache_key" to "${properties.promptVersion}-insights-v2",
        "metadata" to mapOf("prompt_version" to "${properties.promptVersion}-insights-v2"),
    )

    private fun executeRequest(payload: Map<String, Any>): String {
        val response = restClient.post()
            .uri("/responses")
            .header(HttpHeaders.AUTHORIZATION, "Bearer ${properties.apiKey}")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .body(payload)
            .retrieve()
            .body(JsonNode::class.java)
            ?: throw LlmUnavailableException("The AI provider returned an empty response")

        response.path("error").takeUnless { it.isMissingNode || it.isNull }?.let {
            throw LlmUnavailableException("The AI provider returned an error response")
        }
        if (response.path("status").asText() == "incomplete") {
            val reason = response.path("incomplete_details").path("reason").asText("unknown")
            throw LlmUnavailableException("The AI provider response was incomplete ($reason)")
        }

        return response.path("output")
            .flatMap { it.path("content").toList() }
            .firstOrNull { it.path("type").asText() == "output_text" }
            ?.path("text")
            ?.takeIf(JsonNode::isTextual)
            ?.asText()
            ?: throw MalformedLlmResponseException("The AI provider response did not contain output text")
    }

    private fun isRetryable(exception: RuntimeException): Boolean = when (exception) {
        is MalformedLlmResponseException -> true
        is ResourceAccessException -> true
        is RestClientResponseException -> exception.statusCode.value() == 429 || exception.statusCode.is5xxServerError
        else -> false
    }

    private fun normalize(exception: RuntimeException): RuntimeException = when (exception) {
        is MalformedLlmResponseException -> exception
        is LlmConfigurationException -> exception
        is LlmUnavailableException -> exception
        is RestClientResponseException -> LlmUnavailableException(
            "The AI provider request failed with status ${exception.statusCode.value()}",
            exception,
        )
        is ResourceAccessException -> LlmUnavailableException("The AI provider could not be reached", exception)
        else -> LlmUnavailableException("The AI provider request failed", exception)
    }

    private companion object {
        val SYSTEM_PROMPT = """
            You are a warm, practical Korean-language decision coach. Help the user move forward with confidence without taking away their agency.
            Return only the JSON object required by the response schema.
            Keep the complete state cumulative across turns. Preserve confirmed information unless the user corrects it.
            Ask exactly one concise, high-value question per turn until the state is ready for analysis.
            Target 6-8 total questions and never exceed 12. Each question must close one concrete missingInformation item and narrow the decision. Do not ask a question that repeats or paraphrases anything in askedQuestions. Append each new nextQuestion to askedQuestions exactly once.
            Prefer three to five decision criteria. Once there are two explicit options, at least two useful criteria, concrete trade-offs, and a detectable preference or decision rule, set readyToAnalyze immediately instead of prolonging the interview.
            When readyToAnalyze is false, provide exactly four short, mutually useful suggestedAnswers tailored to that exact question. The fourth must be a direct-input choice with label "기타 · 직접 입력" and value "__custom__". Never reuse generic confirmation choices unless the question truly asks for confirmation.
            Every suggested answer label and value shown to the user must be natural Korean. Never expose internal option ids such as stay, move, or change_job as an answer value.
            Use suggestionMode ORDERED only when the user must rank priorities; otherwise use SINGLE. When readyToAnalyze is true, suggestedAnswers must be empty.
            For meaningful life choices such as moving, changing jobs, relationships, education, or large purchases, explore the concrete benefits and drawbacks of both acting and staying. Ask at least one question that reveals which future gives the user more relief, energy, excitement, or regret avoidance.
            Infer userLeaningOptionId only from the user's own explicit words. Record short supporting paraphrases in userLeaningEvidence. Otherwise use null and an empty list.
            When a leaning is visible, acknowledge it encouragingly and describe the positive effect that choice could create. Avoid repeatedly saying that the choice is entirely up to the user.
            Do not over-analyze trivial preference questions. Never pressure the user, shame hesitation, or present optimism as evidence.
            Separate user facts, user assumptions, and AI inferences. Never present an inference as a fact.
            Use stable lowercase ASCII ids with underscores for options and criteria.
            Weights are provisional values from 0 to 1; final normalization and scoring happen elsewhere.
            Set readyToAnalyze only after at least two explicit options, at least two criteria, and enough factual context are present.
            When readyToAnalyze is false, nextQuestion must contain the same core question asked in assistantMessage.
            When readyToAnalyze is true, nextQuestion must be an empty string and stage must be READY_TO_ANALYZE.

            The input JSON contains the complete current validated state and only the latest user message. Update it cumulatively and keep arrays concise.
        """.trimIndent()

        val INSIGHT_PROMPT = """
            You are a warm, evidence-grounded Korean decision coach. Your deliverable is a practical recommendation, NOT a narration of the score table.
            The numeric scores and normalized weights in the input are final values calculated by server code.
            Never recalculate, change, round differently, or invent a numeric score.
            Present concrete benefits and drawbacks of both the encouraged option and its alternatives. Explain what positive effect the encouraged option can create for this user.
            For every input option, return one optionProfile with exactly 2-3 concrete pros and 2-3 concrete cons. Compare options against each other using criterion scores, weights, user facts, and stated uncertainty. Do not merely repeat the user's sentences or list criterion names.
            Recommend a direction clearly, but state the strongest reason the alternative could still be better and the factual condition that would reverse the recommendation.
            The score leader is only a subjective preference signal, not a required recommendation. Select recommendedOptionId from the input options only when the real context supports it. Use null when a concrete verification step or deferral is more appropriate. Do not override safety, affordability or explicit constraints to affirm a preference.
            Explain the causal link: user-provided circumstance -> practical effect -> why this option is suitable now. Never use numeric scores, score gaps or rankings as pros, cons, rationale or evidence. Assessments tagged USER_ASSUMPTION are subjective, not verified facts, even if their reason sounds confident.
            nextAction must be one immediately doable task. practicalAlternative must offer a concrete lower-risk route beyond the binary choice, e.g. checking whether a trial, rental, repair or waiting period is available. Do not claim such services exist without evidence.
            For a purchase, address the intended use, existing substitutes, total cost, frequency of use and reversibility if supplied. If absent, name the decisive missing information instead of inventing prices, reviews or savings. A grounded 'verify this before buying' is better than an unsupported purchase endorsement.
            Each optionProfile must include bestWhen (the concrete condition making that alternative suitable) and evidenceRefs. Phrase unverified consequences conditionally and distinguish them from user-reported facts.
            Keep the answer concise: prefer two useful pros and cons per option, two focused insights and two or three actionable steps. Each should add new decision-relevant information rather than repeating the verdict.
            Be clear and directional. Do not repeat generic phrases such as 'the choice is yours'. Never pressure, diagnose, shame hesitation, or invent certainty.
            evidenceRefs must copy exact strings from knownFacts or userLeaningEvidence only. assumptionRefs must copy exact strings from assumptions or aiInferences only. Use empty arrays when no such evidence exists; never fabricate sources, citations, URLs or external research. User-reported facts are not independently verified. If there is no supporting evidence, set recommendedOptionId to null and confidence to LOW.
            Make action steps specific, ordered, time-bounded, and testable with a clear doneWhen condition.
            Return only the JSON object required by the response schema.
        """.trimIndent()
    }
}

class LlmConfigurationException(message: String) : RuntimeException(message)
class LlmUnavailableException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
