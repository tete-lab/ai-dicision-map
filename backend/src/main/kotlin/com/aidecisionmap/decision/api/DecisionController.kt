package com.aidecisionmap.decision.api

import com.aidecisionmap.decision.service.DecisionConversationService
import com.aidecisionmap.decision.result.DecisionAnalysisService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/decisions")
class DecisionController(
    private val conversationService: DecisionConversationService,
    private val analysisService: DecisionAnalysisService,
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody request: DecisionMessageRequest): DecisionTurnResponse =
        conversationService.createDecision(request.message)

    @PostMapping("/{sessionId}/messages")
    fun addMessage(
        @PathVariable sessionId: String,
        @Valid @RequestBody request: DecisionMessageRequest,
    ): DecisionTurnResponse = conversationService.addMessage(sessionId, request.message)

    @GetMapping("/{sessionId}/state")
    fun getState(@PathVariable sessionId: String): DecisionStateResponse =
        conversationService.getState(sessionId)

    @PostMapping("/{sessionId}/analyze")
    fun analyze(
        @PathVariable sessionId: String,
        @Valid @RequestBody request: AnalyzeDecisionRequest,
    ): DecisionResultResponse = analysisService.analyze(sessionId, request)

    @PostMapping("/{sessionId}/enrich")
    fun enrich(@PathVariable sessionId: String): DecisionResultResponse =
        analysisService.enrich(sessionId)

    @GetMapping("/{sessionId}/result")
    fun getResult(@PathVariable sessionId: String): DecisionResultResponse =
        analysisService.getResult(sessionId)
}
