package com.interviewbot.backend.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** Reasoning settings for the independent specialists; model names inherit the shared default. */
@Validated
@ConfigurationProperties("question-bank.agents")
public record BankAgentProperties(
    @DefaultValue @Valid ModelOptions generation, @DefaultValue @Valid ModelOptions validation) {

  public record ModelOptions(
      @Size(max = 150) String model,
      @DefaultValue("true") boolean thinkingEnabled,
      @DefaultValue("0.6") @DecimalMin("0.0") @DecimalMax("2.0") double temperature,
      @DefaultValue("8192") @Min(512) @Max(32768) int maxOutputTokens,
      @DefaultValue("360") @Min(1) @Max(1200) int timeoutSeconds) {
    public String modelOr(String fallback) {
      return model == null || model.isBlank() ? fallback : model.trim();
    }
  }

  public String modelForAgent(String agent, String fallback) {
    return switch (agent) {
      case "generation" -> generation.modelOr(fallback);
      case "validation", "question_validation" -> validation.modelOr(fallback);
      default -> fallback;
    };
  }
}
