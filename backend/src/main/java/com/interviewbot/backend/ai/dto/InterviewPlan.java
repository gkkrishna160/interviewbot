package com.interviewbot.backend.ai.dto;

import java.util.List;

public record InterviewPlan(
    List<String> topics,
    Difficulty startingDifficulty,
    int estimatedQuestionCount
) {
}
