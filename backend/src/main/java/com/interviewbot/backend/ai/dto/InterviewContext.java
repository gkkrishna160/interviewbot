package com.interviewbot.backend.ai.dto;

import java.util.List;

public record InterviewContext(
    Long interviewId,
    String company,
    String role,
    String experienceLevel,
    List<String> languages,
    List<String> frameworks,
    List<String> topics,
    int durationMinutes,
    Question currentQuestion,
    List<QuestionHistoryItem> previousQuestions,
    Difficulty currentDifficulty,
    long remainingMinutes
) {
}
