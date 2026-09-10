package com.interviewbot.backend.interview.dto;

import com.interviewbot.backend.ai.dto.Difficulty;
import com.interviewbot.backend.ai.dto.QuestionType;
import java.util.List;

public record QuestionResponse(
    Long id,
    String question,
    String topic,
    Difficulty difficulty,
    QuestionType questionType,
    int sequence,
    List<String> expectedConcepts
) {
}
