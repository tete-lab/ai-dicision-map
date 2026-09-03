package com.aidecisionmap.common.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.CorsRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class WebConfig(
    @Value("\${app.cors.allowed-origins:http://localhost:3007}")
    private val allowedOrigins: String,
) : WebMvcConfigurer {
    override fun addCorsMappings(registry: CorsRegistry) {
        registry.addMapping("/api/**")
            .allowedOrigins(*allowedOrigins.split(",").map(String::trim).filter(String::isNotEmpty).toTypedArray())
            .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
            .allowedHeaders("*")
    }
}
