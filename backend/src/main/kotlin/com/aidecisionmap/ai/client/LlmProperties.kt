package com.aidecisionmap.ai.client

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "app.llm")
class LlmProperties {
    var provider: String = "openai"
    var baseUrl: String = "https://api.openai.com/v1"
    var apiKey: String = ""
    var conversationModel: String = "gpt-5.6-luna"
    var narrativeModel: String = "gpt-5.6-terra"
    var conversationReasoningEffort: String = "none"
    var narrativeReasoningEffort: String = "low"
    var promptVersion: String = "decision-coach-v4"
    var conversationMaxOutputTokens: Int = 3_500
    var narrativeMaxOutputTokens: Int = 3_500
}
