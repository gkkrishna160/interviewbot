package com.interviewbot.backend.ai.dto;

public record Evaluation(
    int relevanceScore,
    int technicalScore,
    int correctnessScore,
    String feedback,
    boolean answeredCorrectly,
    boolean needsRetry,
    String followUpQuestion
) {
}
