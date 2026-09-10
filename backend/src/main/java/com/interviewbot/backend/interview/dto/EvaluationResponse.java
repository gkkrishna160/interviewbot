package com.interviewbot.backend.interview.dto;

public record EvaluationResponse(
    Long id,
    int relevanceScore,
    int technicalScore,
    int correctnessScore,
    String feedback,
    boolean answeredCorrectly,
    boolean needsRetry,
    String followUpQuestion
) {
}
