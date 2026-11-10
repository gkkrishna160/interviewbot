package com.interviewbot.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("app")
public record AppProperties(@DefaultValue("interview_mode") Mode mode) {
  public AppProperties {
    java.util.Objects.requireNonNull(mode, "app.mode is required");
  }

  public enum Mode {
    INTERVIEW_MODE,
    GENERATION_MODE
  }

  public boolean interviewMode() {
    return mode == Mode.INTERVIEW_MODE;
  }

  public void requireInterview() {
    if (!interviewMode())
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.FORBIDDEN,
          "Mock interviews are disabled in generation_mode");
  }

  public void requireGeneration() {
    if (interviewMode())
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.FORBIDDEN,
          "Question-bank agents and writes are disabled in interview_mode");
  }
}
