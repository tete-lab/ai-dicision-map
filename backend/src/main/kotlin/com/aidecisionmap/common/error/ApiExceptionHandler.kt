package com.aidecisionmap.common.error

import com.aidecisionmap.ai.client.LlmConfigurationException
import com.aidecisionmap.ai.client.LlmUnavailableException
import com.aidecisionmap.ai.schema.MalformedLlmResponseException
import com.aidecisionmap.decision.service.DecisionSessionNotFoundException
import com.aidecisionmap.decision.service.DecisionStateUnavailableException
import com.aidecisionmap.decision.result.DecisionNotReadyException
import com.aidecisionmap.decision.result.DecisionResultNotFoundException
import org.springframework.http.HttpStatus
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.time.Instant

@RestControllerAdvice
class ApiExceptionHandler {
    @ExceptionHandler(DecisionSessionNotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun notFound(exception: DecisionSessionNotFoundException) =
        ApiError("DECISION_NOT_FOUND", exception.message ?: "Decision session was not found")

    @ExceptionHandler(DecisionStateUnavailableException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun stateUnavailable(exception: DecisionStateUnavailableException) =
        ApiError("DECISION_STATE_UNAVAILABLE", exception.message ?: "Decision state is not available")

    @ExceptionHandler(DecisionNotReadyException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun decisionNotReady(exception: DecisionNotReadyException) =
        ApiError("DECISION_NOT_READY", exception.message ?: "Decision is not ready to analyze")

    @ExceptionHandler(DecisionResultNotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun resultNotFound(exception: DecisionResultNotFoundException) =
        ApiError("DECISION_RESULT_NOT_FOUND", exception.message ?: "Decision result was not found")

    @ExceptionHandler(IllegalArgumentException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun invalidAnalysis(exception: IllegalArgumentException) =
        ApiError("INVALID_ANALYSIS_INPUT", exception.message ?: "Analysis input is invalid")

    @ExceptionHandler(MethodArgumentNotValidException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun invalidRequest(exception: MethodArgumentNotValidException): ApiError {
        val message = exception.bindingResult.fieldErrors.firstOrNull()?.defaultMessage
            ?: "요청 내용을 확인해주세요."
        return ApiError("INVALID_REQUEST", message)
    }

    @ExceptionHandler(LlmConfigurationException::class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    fun llmNotConfigured(exception: LlmConfigurationException) =
        ApiError("LLM_NOT_CONFIGURED", exception.message ?: "AI provider is not configured")

    @ExceptionHandler(MalformedLlmResponseException::class, LlmUnavailableException::class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    fun llmFailure(exception: RuntimeException) =
        ApiError("LLM_RESPONSE_ERROR", exception.message ?: "AI provider request failed")
}

data class ApiError(
    val code: String,
    val message: String,
    val timestamp: Instant = Instant.now(),
)
