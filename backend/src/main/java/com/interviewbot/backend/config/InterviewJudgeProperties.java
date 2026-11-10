package com.interviewbot.backend.config;

import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** Interactive assessments have their own model and latency budget. */
@Validated
@ConfigurationProperties("interview.judge")
public record InterviewJudgeProperties(
    @DefaultValue("qwen3:4b") @NotBlank @Size(max = 150) String model,
    @DefaultValue("false") boolean thinkingEnabled,
    @DefaultValue("1024") @Min(256) @Max(8192) int maxOutputTokens,
    @DefaultValue("8192") @Min(2048) @Max(32768) int contextWindow,
    @DefaultValue("90") @Min(1) @Max(600) int timeoutSeconds) {}
