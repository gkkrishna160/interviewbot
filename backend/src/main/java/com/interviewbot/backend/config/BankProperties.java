package com.interviewbot.backend.config;

import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("question-bank")
public record BankProperties(
    @DefaultValue("false") boolean schedulingEnabled,
    @DefaultValue("10") @Min(1) @Max(20) int generationBatchSize,
    @DefaultValue("15") @Min(1) @Max(10000) int minimumQuestionsPerTopic,
    @DefaultValue("60") @Min(10) int minimumFrequencySeconds,
    @DefaultValue("10") @Min(1) @Max(100) int validationBatchSize,
    @DefaultValue("2") @Min(0) @Max(5) int retryLimit,
    @DefaultValue("180") @Min(1) @Max(600) int executionTimeoutSeconds,
    @DefaultValue("1800") @Min(1) @Max(3600) int cycleTimeoutSeconds,
    @DefaultValue("8") @Min(2) @Max(30) int maxLlmCalls,
    @DefaultValue("10") @Min(1) @Max(50) int similarityCandidates,
    @DefaultValue("70") @Min(0) @Max(100) int minimumQualityScore,
    @DefaultValue("70") @Min(0) @Max(100) int minimumRelevanceScore,
    @DefaultValue("ollama") @Pattern(regexp = "ollama|openai-compatible") String provider,
    @DefaultValue("qwen3:8b") @NotBlank @Size(max = 150) String model,
    @DefaultValue("http://localhost:11434") @NotBlank String baseUrl,
    @DefaultValue("") String apiKey) {}
