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
        return turn.toResponse(session.publicId, "GUIDED")
    }

    @Transactional
    fun addMessage(sessionId: String, message: String): DecisionTurnResponse {
        val session = findSession(sessionId)
        val previousState = readState(session)
        val cleanMessage = message.trim()
        messageRepository.save(MessageEntity(session, MessageRole.USER, cleanMessage))
        // Count actual answers too: old sessions may have stopped recording questions altogether.
        val answeredCount = maxOf(previousState.askedQuestions.size,
            (messageRepository.countBySessionAndRole(session, MessageRole.USER) - 1).coerceAtLeast(0).toInt())
        val recovered = recoverAnswer(previousState, cleanMessage)
        var mode = "AI"
        val turn = if (previousState.readyToAnalyze || answeredCount >= questionLimit(recovered)) {
            mode = "GUIDED"
            completeAtQuestionLimit(recovered, "")
        } else try {
            val generated = llmClient.generateTurn(
                LlmTurnRequest(
                    messages = listOf(TranscriptMessage(MessageRole.USER, cleanMessage)),
                    previousState = previousState,
                ),
            )
            val options = if (recovered.options.map { it.name } != previousState.options.map { it.name }) recovered.options
                else generated.state.options.ifEmpty { recovered.options }
            enforceConversationPolicy(previousState, generated.copy(state = generated.state.copy(
                options = options,
                userLeaningOptionId = generated.state.userLeaningOptionId?.takeIf { id -> options.any { it.id == id } },
                knownFacts = (recovered.knownFacts + generated.state.knownFacts).distinct().takeLast(20),
            )))
        } catch (exception: RuntimeException) {
            if (exception !is LlmConfigurationException &&
                exception !is LlmUnavailableException &&
                exception !is MalformedLlmResponseException
            ) throw exception
            logger.warn("Using a safe conversation turn for session {} after an AI response failure", session.publicId, exception)
            mode = "FALLBACK"
            recoveryTurn(previousState, recovered)
        }
        persistTurn(session, turn)
        return turn.toResponse(session.publicId, mode)
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

    private fun LlmDecisionTurn.toResponse(sessionId: String, mode: String = "AI") =
        DecisionTurnResponse(sessionId, assistantMessage, suggestionMode, suggestedAnswers, state, mode)

    private fun initialTurn(message: String): LlmDecisionTurn {
        val topic = topicFor(message)
        val explicitOptions = DecisionOptionExtractor.extract(message)
        val (title, options, question, suggestions) = if (explicitOptions.size >= 2) {
            InitialTemplate(message.take(120), explicitOptions,
                if (topic == DecisionTopic.FOOD) "오늘 메뉴를 고를 때 가장 중요한 것은 무엇인가요?" else "이 선택에서 가장 중요한 기준은 무엇인가요?",
                priorityValues(topic))
        } else when (topic) {
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
            DecisionTopic.FOOD, DecisionTopic.GENERAL -> InitialTemplate(
                message.take(120),
                emptyList(),
                "지금 비교하고 싶은 선택지를 두 가지 이상 알려주세요.",
                emptyList(),
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

    private fun recoveryTurn(previous: DecisionState, recovered: DecisionState): LlmDecisionTurn {
        val ready = recovered.options.size >= 2 && recovered.criteria.size >= 2 && recovered.knownFacts.size >= 3
        val topic = topicFor(recovered.decisionTitle)
        if (ready) return completeAtQuestionLimit(recovered, "")
        val (question, values) = nextUnaskedQuestion(recovered, topic)
        if (question.isBlank()) return completeAtQuestionLimit(recovered, "")
        val answers = values.toSuggestedAnswers()
        val nextState = recovered.copy(
            stage = if (recovered.options.size < 2) DecisionStage.IDENTIFYING_OPTIONS else DecisionStage.COLLECTING_INFORMATION,
            askedQuestions = (previous.askedQuestions + question).takeLast(MAX_QUESTIONS),
            progress = (previous.progress + 12).coerceAtMost(85),
            readyToAnalyze = false,
            nextQuestion = question,
        )
        val assistantMessage = "답변은 잘 저장했어요. $question"
        return LlmDecisionTurn(assistantMessage, SuggestedAnswerMode.SINGLE, answers, nextState)
    }

    private fun questionLimit(state: DecisionState): Int =
        if (DecisionOptionExtractor.isFood(state.decisionTitle + " " + state.knownFacts.firstOrNull().orEmpty())) 4 else MAX_QUESTIONS

    private fun recoverAnswer(previous: DecisionState, message: String): DecisionState {
        val candidates = DecisionOptionExtractor.extract(message)
        val existing = previous.options.ifEmpty {
            // Old fallback sessions stored explicit corrections only in knownFacts.
            previous.knownFacts.fold(DecisionOptionExtractor.extract(previous.decisionTitle)) { current, fact ->
                val extracted = DecisionOptionExtractor.extract(fact)
                if (extracted.size >= 2 && (current.isEmpty() ||
                        (fact.startsWith("사용자 답변:") && extracted.any { choice -> current.any { it.name == choice.name } }))) extracted
                else current
            }
        }
        val correcting = Regex("선택지[:는 ]|후보[:는 ]|아니[,. ]|대신|바꿀|변경").containsMatchIn(message) ||
            (message.length <= 80 && candidates.any { candidate -> existing.any { it.name == candidate.name } })
        val replacing = candidates.size >= 2 && (existing.size < 2 || correcting || previous.nextQuestion.contains("후보를"))
        val options = if (replacing) candidates.map { candidate ->
            existing.find { it.name == candidate.name } ?: candidate.copy(id = "option_" + UUID.nameUUIDFromBytes(candidate.name.toByteArray()).toString().take(8))
        } else existing
        val optionsChanged = options.map { it.name }.toSet() != previous.options.map { it.name }.toSet()
        val isCriterionAnswer = Regex("기준|중요|조건|이유").containsMatchIn(previous.nextQuestion)
        val vague = Regex("^(네|네\\?|아니요|몰라요|모르겠어요|글쎄요|기타.*|어떤.*|다른 조건은 없어요|__custom__)[.!?]*$").matches(message)
        val name = when (message.trim()) {
            "맵기" -> "맛과 맵기"
            "배부름" -> "양과 포만감"
            else -> message.removeSuffix("이 가장 중요해요").removeSuffix("가 가장 중요해요").trim().take(200)
        }
        val criteria = if (!replacing && isCriterionAnswer && !vague && message.length in 2..80 &&
            previous.criteria.none { normalizeQuestion(it.name) == normalizeQuestion(name) } && previous.criteria.size < 5) {
            previous.criteria + DecisionCriterion("criterion_" + UUID.nameUUIDFromBytes(name.toByteArray()).toString().take(8), name,
                if (previous.criteria.isEmpty()) 0.6 else 0.4, DecisionSourceType.USER_FACT, 0.9)
        } else previous.criteria
        val selected = if (!isCriterionAnswer && !replacing) options.singleOrNull { it.name == message.trim() } else null
        return previous.copy(
            options = options,
            criteria = criteria,
            knownFacts = (previous.knownFacts + "사용자 답변: $message".take(500)).distinct().takeLast(20),
            summary = if (options.size >= 2) "${options.joinToString(" · ") { it.name }}을 비교하고 있어요." +
                if (criteria.isNotEmpty()) " 중요한 기준: ${criteria.joinToString(" · ") { it.name }}" else " 중요한 기준을 확인하고 있어요."
                else previous.summary,
            userLeaningOptionId = selected?.id ?: previous.userLeaningOptionId?.takeIf { id -> options.any { it.id == id } },
            userLeaningEvidence = if (selected != null) listOf("사용자가 ${selected.name} 쪽으로 마음이 간다고 직접 답함")
                else if (optionsChanged) emptyList() else previous.userLeaningEvidence,
            // Changed candidates need their own comparison; unchanged question history still limits fatigue.
            readyToAnalyze = previous.readyToAnalyze && !optionsChanged,
        )
    }

    private fun priorityValues(topic: DecisionTopic) = when (topic) {
        DecisionTopic.JOB -> listOf("성장 기회", "연봉과 보상", "업무 환경과 균형", "__custom__")
        DecisionTopic.MOVING -> listOf("통학·통근 시간", "주거비", "공간과 생활 환경", "__custom__")
        DecisionTopic.FOOD -> listOf("맛과 맵기", "가격", "양과 포만감", "__custom__")
        DecisionTopic.GENERAL -> listOf("비용", "시간", "만족감과 지속 가능성", "__custom__")
    }

    private fun List<String>.toSuggestedAnswers() = map { value ->
        if (value == "__custom__") SuggestedAnswer("기타 · 직접 입력", value) else SuggestedAnswer(value, value)
    }

    private fun topicFor(text: String): DecisionTopic = when {
        DecisionOptionExtractor.isFood(text) -> DecisionTopic.FOOD
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
        val optionNames = state.options.map { it.name }
        val optionChoices = (optionNames.take(3) + if (optionNames.size == 2) listOf("아직 비슷해요") else emptyList()) + "__custom__"
        val candidates = buildList {
            if (state.options.size < 2) {
                add("비교할 후보를 ‘선택지: 후보 A / 후보 B’ 형식으로 입력해주세요." to emptyList())
                return@buildList
            }
            if (state.criteria.isEmpty()) add("이 결정을 내릴 때 가장 중요한 기준은 무엇인가요?" to priorityValues(topic))
            if (state.criteria.size < 2) add("추가로 양보하기 어려운 조건 하나만 골라주세요." to priorityValues(topic).filterNot { value -> state.criteria.any { it.name == value } }.let { values ->
                (values.filterNot { it == "__custom__" } + "다른 조건은 없어요").take(3) + "__custom__"
            })
            if (optionNames.size >= 2) {
                add("${optionNames.take(3).joinToString(" · ") { it.take(80) }}${if (optionNames.size > 3) " 등" else ""} 중 지금 마음이 더 가는 쪽은 어디인가요?" to optionChoices)
                add("각 선택지의 가장 현실적인 단점은 무엇인가요?" to listOf("비용이나 보상", "시간과 에너지", "불확실성과 위험", "__custom__"))
                if (topic != DecisionTopic.FOOD) add("결정 후 6개월을 상상하면 어느 쪽에서 후회가 더 적을 것 같나요?" to optionChoices)
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
        if (state.options.size < 2) return LlmDecisionTurn(
            assistantMessage = "질문은 더 이어가지 않을게요. 후보를 임의로 만들지 않도록 아래 입력란에 실제 선택지 두 개 이상을 입력해주세요. 예: 선택지: 짜장면 / 짬뽕",
            suggestionMode = SuggestedAnswerMode.SINGLE,
            suggestedAnswers = emptyList(),
            state = state.copy(stage = DecisionStage.IDENTIFYING_OPTIONS, knownFacts = facts,
                nextQuestion = "", readyToAnalyze = false, missingInformation = listOf("실제 선택지 두 개 이상")),
        )
        val options = state.options
        val criteria = state.criteria.toMutableList().apply {
            val food = topicFor(state.decisionTitle) == DecisionTopic.FOOD
            if (size < 2 && none { it.id == "practicality" }) add(DecisionCriterion("practicality", if (food) "가격과 포만감" else "비용과 실행 가능성", 0.5, DecisionSourceType.AI_INFERENCE, 0.35))
            if (size < 2 && none { it.id == "expected_effect" }) add(DecisionCriterion("expected_effect", if (food) "오늘의 맛 만족도" else "기대 효과와 지속 만족도", 0.5, DecisionSourceType.AI_INFERENCE, 0.35))
            if (size < 2) add(DecisionCriterion("decision_confidence", "결정 후 확신과 되돌릴 수 있는 정도", 0.5, DecisionSourceType.AI_INFERENCE, 0.3))
        }
        val readyState = state.copy(
            stage = DecisionStage.READY_TO_ANALYZE,
            options = options,
            criteria = criteria,
            knownFacts = facts,
            aiInferences = if (state.criteria.size < 2) (state.aiInferences + "추가 질문 대신 일반적인 비교 기준을 낮은 신뢰도로 보완함. 사용자 평가로 확인이 필요함").distinct().takeLast(20) else state.aiInferences,
            progress = 100,
            readyToAnalyze = true,
            nextQuestion = "",
        )
        return LlmDecisionTurn(
            assistantMessage = "질문은 여기서 마치고, ${options.joinToString(" · ") { it.name }}의 장단점을 비교해볼게요." +
                if (state.criteria.size < 2) " 부족한 비교 기준은 임시로 보완했으니 아래 평가에서 확인해주세요." else " 아래에서 기준별로 한 번씩 평가해주세요.",
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

    private enum class DecisionTopic { JOB, MOVING, FOOD, GENERAL }

    private companion object {
        const val MAX_QUESTIONS = 12
        val logger = LoggerFactory.getLogger(DecisionConversationService::class.java)
    }
}

class DecisionSessionNotFoundException(sessionId: String) :
    RuntimeException("Decision session '$sessionId' was not found")

class DecisionStateUnavailableException(sessionId: String) :
    RuntimeException("Decision state for session '$sessionId' is not available")
