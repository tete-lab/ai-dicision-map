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
import kotlin.test.assertFalse

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

    @Test
    fun `initial meal already contains the two named choices`() {
        val response = service.createDecision("짜장면을 먹을지 탕수육을 먹을지 고민이야")
        assertEquals(listOf("짜장면", "탕수육"), response.state.options.map { it.name })
        assertTrue(response.state.nextQuestion.contains("메뉴"))
        assertTrue(response.suggestedAnswers.any { it.value == "맛과 맵기" })
        assertEquals("GUIDED", response.responseMode)
        verifyNoInteractions(llmClient)
    }

    @Test
    fun `fallback can learn unknown options then finish without repeating`() {
        val initial = service.createDecision("진로를 결정하고 싶어")
        bindSession(initial.state)
        whenever(llmClient.generateTurn(any())).thenThrow(MalformedLlmResponseException("offline"))
        val options = service.addMessage("flow", "선택지: 대학원 진학 / 취업 / 창업")
        assertEquals(listOf("대학원 진학", "취업", "창업"), options.state.options.map { it.name })
        assertEquals("FALLBACK", options.responseMode)
        val first = service.addMessage("flow", "비용")
        val second = service.addMessage("flow", "성장 가능성")
        assertNotEquals(options.state.nextQuestion, first.state.nextQuestion)
        assertTrue(second.state.readyToAnalyze, "first=${first.state}; second=${second.state}")
        assertTrue(second.state.nextQuestion.isEmpty())
        assertTrue(second.suggestedAnswers.isEmpty())
    }

    @Test
    fun `fallback repairs meal choices and asks only for missing criteria`() {
        bindSession(service.createDecision("짜장면을 먹을지 탕수육을 먹을지 고민이야").state)
        whenever(llmClient.generateTurn(any())).thenThrow(MalformedLlmResponseException("offline"))
        val changed = service.addMessage("flow", "짜장면과 짬뽕")
        assertEquals(listOf("짜장면", "짬뽕"), changed.state.options.map { it.name })
        val first = service.addMessage("flow", "맵기")
        val ready = service.addMessage("flow", "배부름")
        assertTrue(ready.state.readyToAnalyze)
        assertEquals(listOf("맛과 맵기", "양과 포만감"), ready.state.criteria.map { it.name })
        assertNotEquals(first.state.nextQuestion, changed.state.nextQuestion)
        assertFalse(ready.state.options.any { it.name.contains("유지") })
    }

    @Test
    fun `repeated vague meal answers end by the fourth question`() {
        bindSession(service.createDecision("짜장면과 짬뽕 중 뭐 먹을까?").state)
        whenever(llmClient.generateTurn(any())).thenThrow(MalformedLlmResponseException("offline"))
        val questions = mutableListOf<String>()
        var ready = false
        for (index in 1..4) {
            val response = service.addMessage("flow", "네?")
            assertTrue(response.state.criteria.none { it.name == "네?" })
            if (response.state.readyToAnalyze) { ready = true; break }
            assertTrue(response.state.nextQuestion.isNotBlank())
            assertFalse(questions.contains(response.state.nextQuestion))
            questions += response.state.nextQuestion
        }
        assertTrue(ready)
    }

    @Test
    fun `old stalled session uses real message count for the hard stop`() {
        bindSession(sampleTurn().state.copy(askedQuestions = emptyList(), nextQuestion = ""))
        whenever(messageRepository.countBySessionAndRole(any(), any())).thenReturn(15L)
        val response = service.addMessage("flow", "시간이 중요해요")
        assertTrue(response.state.readyToAnalyze)
        assertTrue(response.state.criteria.all { it.sourceType == DecisionSourceType.AI_INFERENCE })
        verifyNoInteractions(llmClient)
    }

    @Test
    fun `missing options at limit asks for explicit input instead of inventing choices`() {
        bindSession(service.createDecision("고민을 정리하고 싶어요").state)
        whenever(messageRepository.countBySessionAndRole(any(), any())).thenReturn(15L)
        val response = service.addMessage("flow", "모르겠어요")
        assertTrue(response.state.options.isEmpty())
        assertFalse(response.state.readyToAnalyze)
        assertTrue(response.state.nextQuestion.isEmpty())
        assertTrue(response.suggestedAnswers.isEmpty())
        val recovered = service.addMessage("flow", "선택지: 자격증 공부 / 대학원 진학")
        assertEquals(listOf("자격증 공부", "대학원 진학"), recovered.state.options.map { it.name })
        assertTrue(recovered.state.readyToAnalyze)
        verifyNoInteractions(llmClient)
    }

    private fun bindSession(state: DecisionState) {
        val session = DecisionSessionEntity(publicId = "flow", title = state.decisionTitle, stage = state.stage,
            summary = state.summary, progress = state.progress, stateJson = objectMapper.writeValueAsString(state), promptVersion = "test-v1")
        whenever(sessionRepository.findByPublicId("flow")).thenReturn(session)
    }

    @Test
    fun `exhausted recovery questions finish instead of returning blank prompts`() {
        bindSession(service.createDecision("아이폰 vs 갤럭시").state)
        whenever(llmClient.generateTurn(any())).thenThrow(MalformedLlmResponseException("offline"))
        val questions = mutableSetOf<String>()
        var ready = false
        repeat(12) {
            if (!ready) {
                val response = service.addMessage("flow", "모르겠어요")
                ready = response.state.readyToAnalyze
                if (!ready) {
                    assertTrue(response.state.nextQuestion.isNotBlank())
                    assertTrue(questions.add(response.state.nextQuestion))
                }
            }
        }
        assertTrue(ready)
    }

    @Test
    fun `everyday question cap also applies to successful AI responses`() {
        val meal = service.createDecision("짜장면 vs 짬뽕").state.copy(askedQuestions = (1..4).map { "질문 $it" })
        bindSession(meal)
        val response = service.addMessage("flow", "오늘은 짬뽕이 좋아요")
        assertTrue(response.state.readyToAnalyze)
        assertEquals(listOf("짜장면", "짬뽕"), response.state.options.map { it.name })
        verifyNoInteractions(llmClient)
    }

    @Test
    fun `legacy meal session recovers latest explicit alternatives from stored answers`() {
        bindSession(service.createDecision("짜장면을 먹을지 탕수육을 먹을지 고민이야").state.copy(
            options = emptyList(), nextQuestion = "", askedQuestions = emptyList(),
            knownFacts = listOf("사용자 답변: 짜장면과 탕수육", "사용자 답변: 짜장면과 짬뽕", "사용자 답변: 가격은 짬뽕이 500원 더 비쌈"),
        ))
        whenever(messageRepository.countBySessionAndRole(any(), any())).thenReturn(15L)
        val response = service.addMessage("flow", "비교해줘")
        assertEquals(listOf("짜장면", "짬뽕"), response.state.options.map { it.name })
        assertTrue(response.state.readyToAnalyze)
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
