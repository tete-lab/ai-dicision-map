package com.aidecisionmap.common.health

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@WebMvcTest(HealthController::class)
class HealthControllerTest(
    @Autowired private val mockMvc: MockMvc,
) {

    @Test
    fun `health endpoint reports the API as available`() {
        mockMvc.get("/api/v1/health") {
            accept = org.springframework.http.MediaType.APPLICATION_JSON
        }.andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith(org.springframework.http.MediaType.APPLICATION_JSON) }
            jsonPath("$.status") { value("UP") }
            jsonPath("$.service") { value("ai-decision-map-api") }
        }
    }
}
