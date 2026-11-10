package com.interviewbot.backend.exceptions;

public class LLMException extends RuntimeException {
  public LLMException(String message) {
    super(message);
  }

  public LLMException(String message, Throwable cause) {
    super(message, cause);
  }
}
