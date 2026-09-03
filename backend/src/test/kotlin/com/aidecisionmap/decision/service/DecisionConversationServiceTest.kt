package com.aidecisionmap.decision.service

import com.aidecisionmap.ai.client.LlmClient
import com.aidecisionmap.ai.client.LlmProperties
import com.aidecisionmap.ai.client.LlmTurnRequest
import com.aidecisionmap.ai.schema.MalformedLlmResponseException
import com.aidecisionmap.conversation.domain.MessageEntity
import com.aidecisionmap.conversation.domain.MessageRepository
import com.aidecisionmap.conversation.domain.MessageRole
import com.aidecisionmap.decision.domain.DecisionOption
import com.aidecisionmap.decision.domain.DecisionCriterion
import com.aidecisionmap.decision.domain.DecisionSourceType
import com.aidecisionmap.decision.domain.DecisionSessionEntity
import com.aidecisionmap.decision.domain.DecisionSessionRepository
import com.aidecisionmap.decision.domain.DecisionStage
import com.aidecisionmap.decision.domain.DecisionState
import com.aidecisionmap.decision.domain.LlmDecisionTurn
import com.aidecisionmap.decision.domain.SuggestedAnswer
import com.aidecisionmap.decision.domain.SuggestedAnswerMode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DecisionConversationServiceTest {
    private val sessionRepository = mock<DecisionSessionRepository>()
    private val messageRepository = mock<MessageRepository>()
    private val llmClient = mock<LlmClient>()
    private val objectMapper = ObjectMapper().registerKotlinModule()
    private val properties = LlmProperties().apply { promptVersion = "test-v1" }
    private val service = DecisionConversationService(
        sessionRepository,
        messageRepository,
        llmClient,
        objectMapper,
        properties,
    )

    @BeforeEach
    fun configureRepositorySaves() {
        whenever(sessionRepository.save(any())).thenAnswer { it.arguments[0] as DecisionSessionEntity }
        whenever(messageRepository.save(any())).thenAnswer { it.arguments[0] as MessageEntity }
    }

    @Test
    fun `creates a session and persists both sides of the conversation`() {
        val response = service.createDecision("  이직을 해야 할지 고민돼  ")

        assertNotNull(response.sessionId)
        assertEquals("이직 여부", response.state.decisionTitle)
        assertEquals(4, response.suggestedAnswers.size)
        verifyNoInteractions(llmClient)
        verify(sessionRepository, times(2)).save(any())
        val messages = argumentCaptor<MessageEntity>()
        verify(messageRepository, times(2)).save(messages.capture())
        assertEquals(listOf(MessageRole.USER, MessageRole.ASSISTANT), messages.allValues.map { it.role })
        assertEquals("이직을 해야 할지 고민돼", messages.firstValue.content)
    }

    @Test
    fun `continues with the stored state and latest user answer`() {
        val state = sampleTurn().state
        val session = DecisionSessionEntity(
            publicId = "session-1",
            title = state.decisionTitle,
            stage = state.stage,
            progress = state.progress,
            summary = state.summary,
            stateJson = objectMapper.writeValueAsString(state),
            promptVersion = "test-v1",
        )
        val firstUser = MessageEntity(session, MessageRole.USER, "이직 고민")
        val firstAssistant = MessageEntity(session, MessageRole.ASSISTANT, "다음 질문입니다.")
        val secondUser = MessageEntity(session, MessageRole.USER, "성장이 중요해")
        whenever(sessionRepository.findByPublicId("session-1")).thenReturn(session)
        whenever(llmClient.generateTurn(any())).thenReturn(sampleTurn())

        val response = service.addMessage("session-1", " 성장이 중요해 ")

        val request = argumentCaptor<LlmTurnRequest>()
        verify(llmClient).generateTurn(request.capture())
        assertEquals(state, request.firstValue.previousState)
        assertEquals(1, request.firstValue.messages.size)
        assertEquals("성장이 중요해", request.firstValue.messages.last().content)
        assertNotEquals(state.nextQuestion, response.state.nextQuestion)
    }

    @Test
    fun `keeps the conversation moving when AI JSON is malformed`() {
        val state = sampleTurn().state
        val session = DecisionSessionEntity(
            publicId = "session-safe",
            title = state.decisionTitle,
            stage = state.stage,
            progress = state.progress,
            summary = state.summary,
            stateJson = objectMapper.writeValueAsString(state),
            promptVersion = "test-v1",
        )
        whenever(sessionRepository.findByPublicId("session-safe")).thenReturn(session)
        whenever(llmClient.generateTurn(any())).thenThrow(MalformedLlmResponseException("bad json"))

        val response = service.addMessage("session-safe", "성장 기회")

        assertTrue(response.assistantMessage.startsWith("답변은 잘 저장했어요"))
        assertEquals(1, response.state.criteria.size)
        assertEquals(4, response.suggestedAnswers.size)
    }

    @Test
    fun `stops asking after twelve questions and proceeds to analysis`() {
        val state = sampleTurn().state.copy(
            criteria = listOf(
                DecisionCriterion("growth", "성장 기회", 0.6, DecisionSourceType.USER_FACT, 1.0),
                DecisionCriterion("balance", "일과 삶의 균형", 0.4, DecisionSourceType.USER_FACT, 1.0),
            ),
            askedQuestions = (1..12).map { "서로 다른 확인 질문 $it" },
        )
        val session = DecisionSessionEntity(
            publicId = "session-limit", title = state.decisionTitle, stage = state.stage,
            progress = 92, summary = state.summary, stateJson = objectMapper.writeValueAsString(state), promptVersion = "test-v1",
        )
        whenever(sessionRepository.findByPublicId("session-limit")).thenReturn(session)

        val response = service.addMessage("session-limit", "이직 쪽이 더 기대돼요")

        assertTrue(response.state.readyToAnalyze)
        assertEquals("", response.state.nextQuestion)
        assertEquals(12, response.state.askedQuestions.size)
        verifyNoInteractions(llmClient)
    }

    private fun sampleTurn() = LlmDecisionTurn(
        assistantMessage = "다음 질문입니다.",
        suggestionMode = SuggestedAnswerMode.SINGLE,
        suggestedAnswers = listOf("성장", "보상", "균형", "__custom__").map {
            SuggestedAnswer(if (it == "__custom__") "기타 · 직접 입력" else it, it)
        },
        state = DecisionState(
            decisionTitle = "이직 여부",
            summary = "이직 여부를 정리하고 있다.",
            stage = DecisionStage.IDENTIFYING_OPTIONS,
            options = listOf(
                DecisionOption("stay", "현재 회사에 남기"),
                DecisionOption("move", "새 회사로 이직"),
            ),
            criteria = emptyList(),
            knownFacts = listOf("이직을 고민 중이다"),
            assumptions = emptyList(),
            aiInferences = emptyList(),
            missingInformation = listOf("중요 기준"),
            askedQuestions = listOf("무엇이 가장 중요한가요?"),
            progress = 30,
            readyToAnalyze = false,
            nextQuestion = "무엇이 가장 중요한가요?",
        ),
    )
}
