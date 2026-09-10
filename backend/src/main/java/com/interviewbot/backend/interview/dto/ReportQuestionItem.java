package com.interviewbot.backend.interview.dto;

public record ReportQuestionItem(
    QuestionResponse question,
    String answer,
    EvaluationResponse evaluation
) {
}
