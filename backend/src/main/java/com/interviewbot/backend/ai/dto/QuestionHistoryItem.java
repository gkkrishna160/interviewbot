package com.interviewbot.backend.ai.dto;

public record QuestionHistoryItem(
    String question,
    String topic,
    Difficulty difficulty,
    String answer,
    Evaluation evaluation
) {
}
