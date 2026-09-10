package com.interviewbot.backend.ai.dto;

import java.util.List;

public record Question(
    String question,
    String topic,
    Difficulty difficulty,
    QuestionType questionType,
    List<String> expectedConcepts
) {
}
