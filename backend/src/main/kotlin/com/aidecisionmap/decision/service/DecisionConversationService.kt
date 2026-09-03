package com.aidecisionmap.decision.service

import com.aidecisionmap.ai.client.LlmClient
import com.aidecisionmap.ai.client.LlmConfigurationException
import com.aidecisionmap.ai.client.LlmTurnRequest
import com.aidecisionmap.ai.client.LlmUnavailableException
import com.aidecisionmap.ai.client.TranscriptMessage
import com.aidecisionmap.ai.client.LlmProperties
import com.aidecisionmap.ai.schema.MalformedLlmResponseException
import com.aidecisionmap.conversation.domain.MessageEntity
import com.aidecisionmap.conversation.domain.MessageRepository
import com.aidecisionmap.conversation.domain.MessageRole
import com.aidecisionmap.decision.api.DecisionStateResponse
import com.aidecisionmap.decision.api.DecisionTurnResponse
import com.aidecisionmap.decision.domain.DecisionSessionEntity
import com.aidecisionmap.decision.domain.DecisionSessionRepository
import com.aidecisionmap.decision.domain.DecisionCriterion
import com.aidecisionmap.decision.domain.DecisionOption
import com.aidecisionmap.decision.domain.DecisionSourceType
import com.aidecisionmap.decision.domain.DecisionStage
import com.aidecisionmap.decision.domain.DecisionState
import com.aidecisionmap.decision.domain.LlmDecisionTurn
import com.aidecisionmap.decision.domain.SuggestedAnswer
import com.aidecisionmap.decision.domain.SuggestedAnswerMode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class DecisionConversationService(
    private val sessionRepository: DecisionSessionRepository,
    private val messageRepository: MessageRepository,
    private val llmClient: LlmClient,
    private val objectMapper: ObjectMapper,
    private val llmProperties: LlmProperties,
) {
    @Transactional
    fun createDecision(message: String): DecisionTurnResponse {
        val cleanMessage = message.trim()
        val session = sessionRepository.save(
            DecisionSessionEntity(
                publicId = UUID.randomUUID().toString(),
                title = cleanMessage.take(255),
                stage = DecisionStage.STARTED,
                promptVersion = llmProperties.promptVersion,
            ),
        )
        messageRepository.save(MessageEntity(session, MessageRole.USER, cleanMessage))

        val turn = initialTurn(cleanMessage)
        persistTurn(session, turn)
        return turn.toResponse(session.publicId)
    }

    @Transactional
    fun addMessage(sessionId: String, message: String): DecisionTurnResponse {
        val session = findSession(sessionId)
        val previousState = readState(session)
        val cleanMessage = message.trim()
        messageRepository.save(MessageEntity(session, MessageRole.USER, cleanMessage))
        val turn = if (previousState.askedQuestions.size >= MAX_QUESTIONS) {
            completeAtQuestionLimit(previousState, cleanMessage)
        } else try {
            enforceConversationPolicy(previousState, llmClient.generateTurn(
                LlmTurnRequest(
                    messages = listOf(TranscriptMessage(MessageRole.USER, cleanMessage)),
                    previousState = previousState,
                ),
            ))
        } catch (exception: RuntimeException) {
            if (exception !is LlmConfigurationException &&
                exception !is LlmUnavailableException &&
                exception !is MalformedLlmResponseException
            ) throw exception
            logger.warn("Using a safe conversation turn for session {} after an AI response failure", session.publicId, exception)
            recoveryTurn(previousState, cleanMessage)
        }
        persistTurn(session, turn)
        return turn.toResponse(session.publicId)
    }

    @Transactional(readOnly = true)
    fun getState(sessionId: String): DecisionStateResponse {
        val session = findSession(sessionId)
        return DecisionStateResponse(session.publicId, readState(session))
    }

    private fun persistTurn(session: DecisionSessionEntity, turn: LlmDecisionTurn) {
        session.title = turn.state.decisionTitle
        session.stage = turn.state.stage
        session.progress = turn.state.progress
        session.summary = turn.state.summary
        session.stateJson = objectMapper.writeValueAsString(turn.state)
        sessionRepository.save(session)
        messageRepository.save(MessageEntity(session, MessageRole.ASSISTANT, turn.assistantMessage))
    }

    private fun findSession(sessionId: String): DecisionSessionEntity =
        sessionRepository.findByPublicId(sessionId)
            ?: throw DecisionSessionNotFoundException(sessionId)

    private fun readState(session: DecisionSessionEntity): DecisionState {
        val stateJson = session.stateJson
            ?: throw DecisionStateUnavailableException(session.publicId)
        return objectMapper.readValue(stateJson, DecisionState::class.java)
    }

    private fun LlmDecisionTurn.toResponse(sessionId: String) =
        DecisionTurnResponse(sessionId, assistantMessage, suggestionMode, suggestedAnswers, state)

    private fun initialTurn(message: String): LlmDecisionTurn {
        val topic = topicFor(message)
        val (title, options, question, suggestions) = when (topic) {
            DecisionTopic.JOB -> InitialTemplate(
                "이직 여부",
                listOf(DecisionOption("stay", "현재 직장에 남기"), DecisionOption("change_job", "새 직장으로 이직하기")),
                "이직을 고민하게 된 가장 큰 이유는 무엇인가요?",
                listOf("성장 기회", "연봉과 보상", "업무 환경과 균형", "__custom__"),
            )
            DecisionTopic.MOVING -> InitialTemplate(
                "이사 여부",
                listOf(DecisionOption("stay", "현재 집 유지하기"), DecisionOption("move", "새 집으로 이사하기")),
                "이사를 고민하게 된 가장 큰 이유는 무엇인가요?",
                listOf("통학·통근 시간", "주거비", "공간과 생활 환경", "__custom__"),
            )
            DecisionTopic.GENERAL -> InitialTemplate(
                message.take(120),
                emptyList(),
                "지금 비교하고 싶은 선택지를 두 가지 이상 알려주세요.",
                listOf("현재 상태 유지", "새로운 선택 시도", "두 방향을 함께 비교", "__custom__"),
            )
        }
        val answers = suggestions.toSuggestedAnswers()
        val state = DecisionState(
            decisionTitle = title,
            summary = "‘${message.take(160)}’ 고민을 빠르게 구조화하고 있어요.",
            stage = if (options.size >= 2) DecisionStage.COLLECTING_CRITERIA else DecisionStage.IDENTIFYING_OPTIONS,
            options = options,
            criteria = emptyList(),
            knownFacts = listOf(message.take(500)),
            assumptions = emptyList(),
            aiInferences = emptyList(),
            missingInformation = if (options.size >= 2) listOf("중요한 판단 기준", "선택지별 실제 조건") else listOf("비교할 선택지", "중요한 판단 기준"),
            askedQuestions = listOf(question),
            progress = if (options.size >= 2) 25 else 15,
            readyToAnalyze = false,
            nextQuestion = question,
        )
        return LlmDecisionTurn(
            assistantMessage = "좋아요. 고민을 바로 정리해볼게요. $question",
            suggestionMode = SuggestedAnswerMode.SINGLE,
            suggestedAnswers = answers,
            state = state,
        )
    }

    private fun recoveryTurn(previous: DecisionState, message: String): LlmDecisionTurn {
        val facts = (previous.knownFacts + "사용자 답변: $message".take(500)).distinct().takeLast(20)
        val collectingCriterion = previous.options.size >= 2 && previous.criteria.size < 2
        val criteria = if (collectingCriterion && message.length <= 80 && message != "__custom__") {
            previous.criteria + DecisionCriterion(
                id = "criterion_${previous.criteria.size + 1}",
                name = message,
                weight = if (previous.criteria.isEmpty()) 0.6 else 0.4,
                sourceType = DecisionSourceType.USER_FACT,
                confidence = 0.9,
            )
        } else previous.criteria
        val ready = previous.options.size >= 2 && criteria.size >= 2 && facts.size >= 3
        val topic = topicFor(previous.decisionTitle)
        val (question, values) = when {
            ready -> "" to emptyList()
            else -> nextUnaskedQuestion(previous.copy(criteria = criteria), topic)
        }
        val answers = values.toSuggestedAnswers()
        val nextState = previous.copy(
            stage = if (ready) DecisionStage.READY_TO_ANALYZE else if (criteria.isEmpty()) DecisionStage.COLLECTING_CRITERIA else DecisionStage.COLLECTING_INFORMATION,
            criteria = criteria,
            knownFacts = facts,
            missingInformation = if (ready) emptyList() else previous.missingInformation,
            askedQuestions = if (question.isBlank()) previous.askedQuestions else (previous.askedQuestions + question).takeLast(MAX_QUESTIONS),
            progress = if (ready) 100 else (previous.progress + 12).coerceAtMost(85),
            readyToAnalyze = ready,
            nextQuestion = question,
        )
        val assistantMessage = if (ready) {
            "답변은 잘 저장했어요. 필요한 핵심 기준이 모였습니다. 이제 선택지별 조건을 확인하고 분석해볼게요."
        } else {
            "답변은 잘 저장했어요. 흐름을 끊지 않고 한 가지만 더 확인할게요. $question"
        }
        return LlmDecisionTurn(assistantMessage, SuggestedAnswerMode.SINGLE, answers, nextState)
    }

    private fun priorityValues(topic: DecisionTopic) = when (topic) {
        DecisionTopic.JOB -> listOf("성장 기회", "연봉과 보상", "업무 환경과 균형", "__custom__")
        DecisionTopic.MOVING -> listOf("통학·통근 시간", "주거비", "공간과 생활 환경", "__custom__")
        DecisionTopic.GENERAL -> listOf("비용", "시간", "만족감과 지속 가능성", "__custom__")
    }

    private fun List<String>.toSuggestedAnswers() = map { value ->
        if (value == "__custom__") SuggestedAnswer("기타 · 직접 입력", value) else SuggestedAnswer(value, value)
    }

    private fun topicFor(text: String): DecisionTopic = when {
        listOf("이직", "퇴사", "직장", "회사").any(text::contains) -> DecisionTopic.JOB
        listOf("이사", "집", "주거", "전학").any(text::contains) -> DecisionTopic.MOVING
        else -> DecisionTopic.GENERAL
    }

    private fun enforceConversationPolicy(previous: DecisionState, generated: LlmDecisionTurn): LlmDecisionTurn {
        if (generated.state.readyToAnalyze) {
            return generated.copy(state = generated.state.copy(askedQuestions = previous.askedQuestions, nextQuestion = ""), suggestedAnswers = emptyList())
        }
        if (previous.askedQuestions.size >= MAX_QUESTIONS) {
            return completeAtQuestionLimit(generated.state, "")
        }

        val question = generated.state.nextQuestion.trim()
        val repeated = previous.askedQuestions.any { isSimilarQuestion(it, question) }
        if (!repeated && question.isNotBlank()) {
            val questions = (previous.askedQuestions + question).take(MAX_QUESTIONS)
            return generated.copy(
                state = generated.state.copy(
                    askedQuestions = questions,
                    progress = maxOf(generated.state.progress, (questions.size * 100 / MAX_QUESTIONS).coerceAtMost(92)),
                ),
            )
        }

        val topic = topicFor(generated.state.decisionTitle)
        val (replacement, values) = nextUnaskedQuestion(generated.state, topic, previous.askedQuestions)
        if (replacement.isBlank()) return completeAtQuestionLimit(generated.state, "")
        return generated.copy(
            assistantMessage = "답변에서 새로운 정보를 반영했어요. 같은 내용을 다시 묻지 않고 다음 핵심으로 넘어갈게요. $replacement",
            suggestionMode = SuggestedAnswerMode.SINGLE,
            suggestedAnswers = values.toSuggestedAnswers(),
            state = generated.state.copy(
                askedQuestions = (previous.askedQuestions + replacement).take(MAX_QUESTIONS),
                nextQuestion = replacement,
            ),
        )
    }

    private fun nextUnaskedQuestion(
        state: DecisionState,
        topic: DecisionTopic,
        history: List<String> = state.askedQuestions,
    ): Pair<String, List<String>> {
        val optionNames = state.options.take(2).map { it.name }
        val candidates = buildList {
            if (state.options.size < 2) add("실제로 비교할 두 선택지를 각각 알려주세요." to listOf("현재 상태 유지", "새로운 선택 시도", "두 방향 함께 비교", "__custom__"))
            if (state.criteria.isEmpty()) add("이 결정을 내릴 때 가장 중요한 기준은 무엇인가요?" to priorityValues(topic))
            if (state.criteria.size < 2) add("첫 번째 기준 다음으로 꼭 지키고 싶은 조건은 무엇인가요?" to priorityValues(topic))
            if (optionNames.size == 2) {
                add("${optionNames[0]}와 ${optionNames[1]} 중 지금 마음이 더 가는 쪽은 어디인가요?" to (optionNames + listOf("아직 비슷해요", "__custom__")))
                add("두 선택지의 가장 현실적인 단점은 각각 무엇인가요?" to listOf("비용이나 보상", "시간과 에너지", "불확실성과 위험", "__custom__"))
                add("결정 후 6개월을 상상하면 어느 쪽에서 후회가 더 적을 것 같나요?" to (optionNames + listOf("아직 판단하기 어려워요", "__custom__")))
            }
            add("결정을 내리기 전에 반드시 확인해야 할 실제 정보 한 가지는 무엇인가요?" to listOf("정확한 비용", "실제 일정", "당사자·전문가 확인", "__custom__"))
            add("어떤 조건이 충족되면 바로 결정할 수 있나요?" to listOf("핵심 조건 충족", "위험이 감당 가능", "작은 시험이 성공", "__custom__"))
        }
        return candidates.firstOrNull { candidate -> history.none { isSimilarQuestion(it, candidate.first) } }
            ?: ("" to emptyList())
    }

    private fun completeAtQuestionLimit(state: DecisionState, latestAnswer: String): LlmDecisionTurn {
        val facts = if (latestAnswer.isBlank()) state.knownFacts else
            (state.knownFacts + "사용자 답변: $latestAnswer".take(500)).distinct().takeLast(20)
        val options = state.options.ifEmpty {
            listOf(DecisionOption("keep_current", "현재 상태 유지하기"), DecisionOption("try_change", "새로운 선택 시도하기"))
        }.let { if (it.size == 1) it + DecisionOption("alternative", "다른 대안 선택하기") else it }
        val criteria = state.criteria.toMutableList().apply {
            if (size < 2 && none { it.id == "practicality" }) add(DecisionCriterion("practicality", "비용과 실행 가능성", 0.5, DecisionSourceType.AI_INFERENCE, 0.35))
            if (size < 2 && none { it.id == "expected_effect" }) add(DecisionCriterion("expected_effect", "기대 효과와 지속 만족도", 0.5, DecisionSourceType.AI_INFERENCE, 0.35))
            if (size < 2) add(DecisionCriterion("decision_confidence", "결정 후 확신과 되돌릴 수 있는 정도", 0.5, DecisionSourceType.AI_INFERENCE, 0.3))
        }
        val readyState = state.copy(
            stage = DecisionStage.READY_TO_ANALYZE,
            options = options,
            criteria = criteria,
            knownFacts = facts,
            aiInferences = if (state.criteria.size < 2) (state.aiInferences + "질문 상한에 도달해 일반적인 실행 가능성과 기대 효과 기준을 낮은 신뢰도로 보완함").distinct() else state.aiInferences,
            progress = 100,
            readyToAnalyze = true,
            nextQuestion = "",
        )
        return LlmDecisionTurn(
            assistantMessage = "충분한 핵심 정보가 모였어요. 질문은 여기서 마치고, 이제 두 선택지의 장단점과 현실적인 대안을 비교해볼게요.",
            suggestionMode = SuggestedAnswerMode.SINGLE,
            suggestedAnswers = emptyList(),
            state = readyState,
        )
    }

    private fun isSimilarQuestion(left: String, right: String): Boolean {
        val a = normalizeQuestion(left)
        val b = normalizeQuestion(right)
        if (a.isBlank() || b.isBlank()) return false
        if (a.contains(b) || b.contains(a)) return true
        val leftPairs = a.windowed(2).toSet()
        val rightPairs = b.windowed(2).toSet()
        if (leftPairs.isEmpty() || rightPairs.isEmpty()) return a == b
        val overlap = leftPairs.intersect(rightPairs).size.toDouble()
        return overlap / minOf(leftPairs.size, rightPairs.size) >= 0.68
    }

    private fun normalizeQuestion(value: String) = value.lowercase().replace(Regex("[^가-힣a-z0-9]"), "")

    private data class InitialTemplate(
        val title: String,
        val options: List<DecisionOption>,
        val question: String,
        val suggestions: List<String>,
    )

    private enum class DecisionTopic { JOB, MOVING, GENERAL }

    private companion object {
        const val MAX_QUESTIONS = 12
        val logger = LoggerFactory.getLogger(DecisionConversationService::class.java)
    }
}

class DecisionSessionNotFoundException(sessionId: String) :
    RuntimeException("Decision session '$sessionId' was not found")

class DecisionStateUnavailableException(sessionId: String) :
    RuntimeException("Decision state for session '$sessionId' is not available")
