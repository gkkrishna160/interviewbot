package com.interviewbot.backend.interview.dto;

import com.interviewbot.backend.interview.entity.InterviewStatus;

public record SubmitAnswerResponse(
    EvaluationResponse evaluation,
    QuestionResponse nextQuestion,
    InterviewStatus status
) {
}
