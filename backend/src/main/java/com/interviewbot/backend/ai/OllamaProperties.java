package com.interviewbot.backend.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "llm.ollama")
public record OllamaProperties(
    String baseUrl,
    String model,
    int connectTimeoutSeconds,
    int readTimeoutSeconds
) {
}
