package com.aidecisionmap.common.health

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1")
class HealthController {
    @GetMapping("/health")
    fun health(): HealthResponse = HealthResponse(
        status = "UP",
        service = "ai-decision-map-api",
    )
}

data class HealthResponse(
    val status: String,
    val service: String,
)

