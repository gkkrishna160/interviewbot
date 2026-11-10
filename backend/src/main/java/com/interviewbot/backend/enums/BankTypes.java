package com.interviewbot.backend.enums;

public final class BankTypes {
  private BankTypes() {}

  public enum Difficulty {
    BEGINNER,
    INTERMEDIATE,
    ADVANCED
  }

  public enum QuestionType {
    FUNDAMENTAL,
    PRACTICAL,
    DEBUGGING,
    SYSTEM_DESIGN,
    ARCHITECTURE,
    SCENARIO
  }

  public enum Status {
    PENDING_REVIEW,
    APPROVED,
    REJECTED,
    DUPLICATE
  }

  public enum Source {
    AI_GENERATED,
    MANUAL
  }

  public enum RunStatus {
    RUNNING,
    SUCCEEDED,
    FAILED,
    TIMED_OUT
  }
}
